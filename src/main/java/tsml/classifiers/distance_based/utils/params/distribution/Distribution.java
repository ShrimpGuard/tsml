package tsml.classifiers.distance_based.utils.params.distribution;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import tsml.classifiers.distance_based.proximity.RandomSource;

/**
 * Purpose: // todo - docs - type the purpose of the code here
 * <p>
 * Contributors: goastler
 */

public interface Distribution<A> extends RandomSource {

    A sample();
}
