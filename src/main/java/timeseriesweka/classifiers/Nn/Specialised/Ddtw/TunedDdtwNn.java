package timeseriesweka.classifiers.Nn.Specialised.Ddtw;

import development.go.Ee.AbstractTuned;
import development.go.Ee.Tuned;
import evaluation.tuning.ParameterSpace;
import timeseriesweka.classifiers.Nn.Specialised.Dtw.DtwNn;
import timeseriesweka.classifiers.Nn.Specialised.Dtw.TunedDtwNn;
import timeseriesweka.measures.dtw.Dtw;
import utilities.Utilities;
import weka.core.Instances;

import java.util.Collections;
import java.util.List;

public class TunedDdtwNn extends Tuned {
    public TunedDdtwNn() {
        setClassifier(new DdtwNn());
    }

    @Override
    public void useTrainInstances(final Instances trainInstances) {
        ParameterSpace parameterSpace = getParameterSpace();
        parameterSpace.addParameter(Dtw.WARPING_WINDOW_KEY, Utilities.linearInterpolate(101, 100));
    }
}
