package development.go;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.converters.FileConverter;
import timeseriesweka.classifiers.NearestNeighbour;
import timeseriesweka.classifiers.ee.constituents.generators.*;
import timeseriesweka.classifiers.ee.iteration.RandomIndexIterator;
import timeseriesweka.measures.DistanceMeasure;
import utilities.ClassifierResults;
import utilities.ClassifierStats;
import utilities.InstanceTools;
import utilities.Utilities;
import utilities.range.Range;
import weka.core.Instances;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SamplingExperiment {

    private SamplingExperiment() {}

    // todo param validation
    @Parameter(names={"-r"}, description="results globalResultsDir", converter= FileConverter.class, required=true)
    private File globalResultsDir;
    @Parameter(names={"-fi"}, description="dataset fold index", required=true)
    private List<Integer> foldIndices;
    @Parameter(names={"-d"}, description="datasets", required=true)
    private List<File> datasets;
    @Parameter(names={"-k"}, description="kill switch file path", required=true)
    private String killSwitchFilePath;

    public static void main(String[] args) {
        SamplingExperiment samplingExperiment = new SamplingExperiment();
        new JCommander(samplingExperiment).parse(args);
        samplingExperiment.run();
    }

    private static void mkdir(File dir) {
        File parentFile = dir.getParentFile();
        if (parentFile != null) {
            mkdir(parentFile);
        }
        if(dir.mkdirs()) {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        }
    }
    
    private static boolean statsCorrupt(File file) {
        try {
            ObjectInputStream in = new ObjectInputStream(
                new GZIPInputStream(
                    new BufferedInputStream(
                        new FileInputStream(file))));
            Object object = in.readObject();
            in.close();
            ClassifierStats stats = (ClassifierStats) object;
            return false;
        } catch (IOException | ClassNotFoundException e) {
            return true;
        }
    }

    private static void writeObjectToFile(Object object, File file) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(
            new GZIPOutputStream(
                new BufferedOutputStream(
                    new FileOutputStream(file))));
        out.writeObject(object);
        out.close();
    }

    private void writeStatsToFile(File file) throws IOException {
        double[][] predictions = nearestNeighbour.predict();
        ClassifierResults results = new ClassifierResults();
        for(int i = 0; i < testInstances.numInstances(); i++) {
            results.storeSingleResult(testInstances.get(i).classValue(), predictions[i]);
        }
        results.setNumInstances(testInstances.numInstances());
        results.setNumClasses(testInstances.numClasses());
        results.findAllStatsOnce();
        results.setTrainTime(nearestNeighbour.getTrainTime());
        results.setTestTime(nearestNeighbour.getTestTime());
        results.setBenchmark(benchmark);
        ClassifierStats stats = new ClassifierStats(results);
        writeObjectToFile(stats, file);
    }

    private long benchmark;
    private NearestNeighbour nearestNeighbour;
    private Instances testInstances;

    public void run() {
        final boolean[] stop = {false};
        File killSwitchFile = new File(killSwitchFilePath);
        Thread killSwitch = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if(!killSwitchFile.exists()) {
                    stop[0] = true;
                    System.out.println("killing");
                }
                try {
                    Thread.sleep(TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        killSwitch.start();
        benchmark = ClassifierResults.benchmark();
        List<ParameterisedSupplier<? extends DistanceMeasure>> parameterisedSuppliers = new ArrayList<>();
        parameterisedSuppliers.add(new DtwParameterisedSupplier());
        parameterisedSuppliers.add(new DdtwParameterisedSupplier());
        parameterisedSuppliers.add(new WdtwParameterisedSupplier());
        parameterisedSuppliers.add(new WddtwParameterisedSupplier());
        parameterisedSuppliers.add(new LcssParameterisedSupplier());
        parameterisedSuppliers.add(new MsmParameterisedSupplier());
        parameterisedSuppliers.add(new TweParameterisedSupplier());
        parameterisedSuppliers.add(new ErpParameterisedSupplier());
        parameterisedSuppliers.add(new EuclideanParameterisedSupplier());
        final int[] parameterBins = new int[] {
            parameterisedSuppliers.size(),
            datasets.size(),
        };
        final int numCombinations = Utilities.numCombinations(parameterBins);
        for(Integer foldIndex : foldIndices) {
            RandomIndexIterator combinationIndexIterator = new RandomIndexIterator();
            combinationIndexIterator.setRange(new Range(0, numCombinations - 1));
            combinationIndexIterator.setSeed(0);
            while (combinationIndexIterator.hasNext() && !stop[0]) {
                int combination = combinationIndexIterator.next();
                combinationIndexIterator.remove();
                int[] parameters = Utilities.fromCombination(combination, parameterBins);
                int parameterIndex = 0;
                ParameterisedSupplier<? extends DistanceMeasure> parameterisedSupplier = parameterisedSuppliers.get(parameters[parameterIndex++]);
                File datasetFile = datasets.get(parameters[parameterIndex++]);
                String datasetName = datasetFile.getName();
                final File resultsDir = new File(globalResultsDir, datasetName);
                mkdir(resultsDir);
                try {
                    Instances dataset = Utilities.loadDataset(datasetFile);
                    Instances[] splitInstances = InstanceTools.resampleInstances(dataset, foldIndex, 0.5);
                    Instances trainInstances = splitInstances[0];
                    parameterisedSupplier.setParameterRanges(trainInstances);
                    testInstances = splitInstances[1];
                    RandomIndexIterator distanceMeasureParameterIterator = new RandomIndexIterator();
                    distanceMeasureParameterIterator.setRange(new Range(0, parameterisedSupplier.size() - 1));
                    distanceMeasureParameterIterator.setSeed(0);
                    while(distanceMeasureParameterIterator.hasNext() && !stop[0]) {
                        int distanceMeasureParameter = distanceMeasureParameterIterator.next();
                        distanceMeasureParameterIterator.remove();
                        DistanceMeasure distanceMeasure = parameterisedSupplier.get(distanceMeasureParameter);
                        nearestNeighbour = new NearestNeighbour();
                        nearestNeighbour.setDistanceMeasure(distanceMeasure);
                        nearestNeighbour.setSeed(foldIndex);
                        nearestNeighbour.setTrainInstances(trainInstances);
                        nearestNeighbour.setTestInstances(testInstances);
                        nearestNeighbour.train();
                        String resultsFilePrefix = "f=" + foldIndex
                            + ",k=" + nearestNeighbour.getK()
                            + ",d=" + nearestNeighbour.getDistanceMeasure()
                            + ",q={" + nearestNeighbour.getDistanceMeasure().getParameters() + "}";
                        int numTrainInstances = trainInstances.numInstances();
                        double nextPercentage = 0;
                        int numTestTickInstances = 0;
                        for(int i = 0, j = 0; i <= numTrainInstances && !stop[0]; i++) {
                            double percentage = (double) i / numTrainInstances;
                            if(percentage >= nextPercentage) {
                                j++;
                                if(i == numTrainInstances) {
                                    System.out.println();
                                }
                                nextPercentage = (double) j / 100;
                                File file = new File(resultsDir, resultsFilePrefix + ",p=" + percentage + ".gzip");
                                if(file.createNewFile() || statsCorrupt(file)) {
                                    while (numTestTickInstances < i && !stop[0]) {
                                        nearestNeighbour.testTick();
                                        if(nearestNeighbour.hasSelectedNewTrainInstance() || !nearestNeighbour.remainingTestTicks()) {
                                            numTestTickInstances++;
                                        }
                                    }
                                    try {
                                        writeStatsToFile(file);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        killSwitch.interrupt();
    }


}
