package machine_learning.classifiers.tuned.incremental;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface BenchmarkEnsembler {
    List<Double> weightVotes(Iterable<Benchmark> benchmarks);

    static BenchmarkEnsembler byScore(Function<Benchmark, Double> scorer) {
        return (benchmarks) -> {
            List<Double> weights = new ArrayList<>();
            for(Benchmark benchmark : benchmarks) {
                weights.add(scorer.apply(benchmark));
            }
            return weights;
        };
    }
}