package tsml.classifiers.distance_based.utils;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Lists;
import evaluation.storage.ClassifierResults;
import experiments.data.DatasetLoading;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import tsml.classifiers.distance_based.utils.classifier_building.ClassifierBuilderFactory;
import tsml.classifiers.distance_based.utils.classifier_building.ClassifierBuilderFactory.ClassifierBuilder;
import tsml.classifiers.distance_based.utils.logging.LogUtils;
import tsml.classifiers.distance_based.utils.logging.Loggable;
import tsml.classifiers.distance_based.utils.memory.MemoryAmount;
import tsml.classifiers.distance_based.utils.parallel.BlockingExecutor;
import tsml.classifiers.distance_based.utils.params.ParamSet;
import tsml.classifiers.distance_based.utils.stopwatch.TimeAmount;
import utilities.FileUtils;
import weka.classifiers.Classifier;
import weka.core.Instances;

/**
 * Purpose: // todo - docs - type the purpose of the code here
 * <p>
 * Contributors: goastler
 */

public class Main {
    // todo use getters and setters internally

    // abide by unix cmdline args convention! single char --> single hyphen, multiple chars --> double hyphen
    // todo getter sna setters
    private final Logger logger = LogUtils.buildLogger(this);
    @Parameter(names = {"-c", "--classifier"}, description = "todo", required = true)
    private List<String> classifierNames = new ArrayList<>();
    @Parameter(names = {"--dd", "--datasetsDir"}, description = "todo", required = true)
    private List<String> datasetDirPaths = new ArrayList<>();
    @Parameter(names = {"-d", "--dataset"}, description = "todo", required = true)
    private List<String> datasetNames = new ArrayList<>();
    @Parameter(names = {"-s", "--seed"}, description = "todo", required = true)
    private List<Integer> seeds = Lists.newArrayList(0);
    @Parameter(names = {"-r", "--resultsDir"}, description = "todo", required = true)
    private String resultsDirPath = null;
    @Parameter(names = {"-p", "--parameters"}, description = "todo", variableArity = true)
    private List<String> classifierParameterStrs = new ArrayList<>();
    private Map<String, ParamSet> bespokeClassifierParametersMap = new HashMap<>();
    private ParamSet universalClassifierParameters = new ParamSet();
    @Parameter(names = {"--acp", "--appendClassifierParameters"}, description = "todo")
    private boolean appendClassifierParameters = false;
    @Parameter(names = {"--ttc", "--trainTimeContract"}, arity = 2, description = "todo")
    private List<String> trainTimeContractStrs = new ArrayList<>();
    private List<TimeAmount> trainTimeContracts = new ArrayList<>();
    @Parameter(names = {"--tmc", "--trainMemoryContract"}, arity = 2, description = "todo")
    private List<String> trainMemoryContractStrs = new ArrayList<>();
    private List<MemoryAmount> trainMemoryContracts = new ArrayList<>();
    @Parameter(names = {"--ptc", "--predictTimeContract"}, arity = 2, description = "todo")
    private List<String> testTimeContractStrs = new ArrayList<>();
    private List<TimeAmount> testTimeContracts = new ArrayList<>();
    @Parameter(names = "--checkpoint", description = "todo")
    private boolean checkpoint = false; // todo swap train contract save / load path over
    @Parameter(names = {"-t", "--threads"}, description = "todo")
    private int numThreads = 1; // <=0 for all available threads
    @Parameter(names = {"--attc", "--appendTrainTimeContract"}, description = "todo")
    private boolean appendTrainTimeContract = false;
    @Parameter(names = {"--atmc", "--appendTrainMemoryContract"}, description = "todo")
    private boolean appendTrainMemoryContract = false;
    @Parameter(names = {"--aptc", "--appendPredictTimeContract"}, description = "todo")
    private boolean appendTestTimeContract = false;
    @Parameter(names = {"-e", "--estimateTrainError"}, description = "todo")
    private boolean estimateTrainError = false;
    @Parameter(names = {"-l", "--logLevel"}, description = "todo")
    private String logLevel = Level.ALL.toString(); // todo set log level in classifier / experiment
    @Parameter(names = {"--of", "--overwriteTrain"}, description = "todo")
    private boolean overwriteTrain = false;
    @Parameter(names = {"--op", "--overwriteTest"}, description = "todo")
    private boolean overwritePredict = false;
    private ClassifierBuilderFactory<Classifier> classifierBuilderFactory =
        ClassifierBuilderFactory.getGlobalInstance(); // todo get this by string, i.e. factory

    public Main(String... args) throws Exception {
        parse(args);
    }

    public static void main(String... args) throws Exception {
        new Main(args).runExperiments();
    }

    public static <A> List<A> convertStringPairs(List<String> strs, BiFunction<String, String, A> func) {
        List<A> objs = new ArrayList<>();
        for(int i = 0; i < strs.size(); i += 2) {
            final String trainContractAmountStr = strs.get(i);
            final String trainContractUnitStr = strs.get(i + 1);
            final A obj = func.apply(trainContractAmountStr, trainContractUnitStr);
            objs.add(obj);
        }
        return objs;
    }

    public static List<TimeAmount> convertStringPairsToTimeAmounts(List<String> strs) {
        List<TimeAmount> times = convertStringPairs(strs, TimeAmount::parse);
        Collections.sort(times);
        return times;
    }

    public static List<MemoryAmount> convertStringPairsToMemoryAmounts(List<String> strs) {
        List<MemoryAmount> times = convertStringPairs(strs, MemoryAmount::parse);
        Collections.sort(times);
        return times;
    }

    public void parse(String... args) throws Exception {
        JCommander.newBuilder()
            .addObject(this)
            .build()
            .parse(args);
        // perform custom parsing here
        parseClassifierParameters();
        parseLogLevel();
        parseTrainMemoryContracts();
        parseTrainTimeContracts();
        parseTestTimeContracts();
    }

    private void parseLogLevel() {
        if(logLevel != null) {
            logger.setLevel(Level.parse(logLevel));
        }
    }

    private void parseTrainTimeContracts() {
        if(trainTimeContractStrs.size() % 2 != 0) {
            throw new IllegalStateException("train time contracts must be a list of pairs, i.e. \"5\" \"minutes\"");
        }
        trainTimeContracts = convertStringPairsToTimeAmounts(trainTimeContractStrs);
        // if there's no train contract we'll put nulls in place. This causes the loop to fire and we'll handle nulls
        // as no contract inside the loop
        if(trainTimeContracts.isEmpty()) {
            trainTimeContracts.add(null);
        }
    }

    private void parseTestTimeContracts() {
        if(testTimeContractStrs.size() % 2 != 0) {
            throw new IllegalStateException("test time contracts must be a list of pairs, i.e. \"5\" \"minutes\"");
        }
        testTimeContracts = convertStringPairsToTimeAmounts(testTimeContractStrs);
        if(testTimeContracts.isEmpty()) {
            testTimeContracts.add(null);
        }
    }

    private void parseTrainMemoryContracts() {
        if(trainMemoryContractStrs.size() % 2 != 0) {
            throw new IllegalStateException("train memory contracts must be a list of pairs, i.e. \"5\" \"minutes\"");
        }
        trainMemoryContracts = convertStringPairsToMemoryAmounts(trainMemoryContractStrs);
        if(trainMemoryContracts.isEmpty()) {
            trainMemoryContracts.add(null);
        }
    }

    private void parseClassifierParameters() throws Exception {
        bespokeClassifierParametersMap = new HashMap<>();
        universalClassifierParameters = new ParamSet();
        Set<String> classifierNamesLookup = new HashSet<>(classifierNames);
        for(int i = 0; i < classifierParameterStrs.size(); ) {
            String str = classifierParameterStrs.get(i++);
            String parameterName;
            String parameterValue;
            ParamSet paramSet;
            if(classifierNamesLookup.contains(str)) {
                // we're in a 3 arity situation, i.e. <classifier name> <parameter name> <parameter value>
                parameterName = classifierParameterStrs.get(i++);
                parameterValue = classifierParameterStrs.get(i++);
                paramSet = bespokeClassifierParametersMap.computeIfAbsent(str, k -> new ParamSet());
            } else {
                // we're in a 2 arity situation, i.e. <parameter name> <parameter value>
                parameterName = str;
                parameterValue = classifierParameterStrs.get(i++);
                paramSet = universalClassifierParameters;
            }
            paramSet.setOptions(parameterName, parameterValue);
        }
    }

    private Instances[] loadData(String name, int seed) {
        for(final String path : datasetDirPaths) {
            try {
                Instances[] data = DatasetLoading.sampleDataset(path, name, seed);
                if(data == null) {
                    throw new Exception();
                }
                return data;
            } catch(Exception ignored) {

            }
        }
        throw new IllegalArgumentException("couldn't load data");
    }

    @Override
    public String toString() {
        return
            "classifierNames=" + classifierNames +
                ", datasetDirPaths=" + datasetDirPaths +
                ", datasetNames=" + datasetNames +
                ", seeds=" + seeds +
                ", resultsDirPath='" + resultsDirPath + '\'' +
                ", trainContracts=" + trainTimeContractStrs +
                ", checkpoint=" + checkpoint +
                ", classifierBuilderFactory=" + classifierBuilderFactory
            ;
    }

    private ExecutorService buildExecutor() {
        int numThreads = this.numThreads;
        if(numThreads < 1) {
            numThreads = Runtime.getRuntime().availableProcessors();
        }
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(numThreads);
        return new BlockingExecutor(threadPool);
    }

    private ParamSet buildParamSetForClassifier(String classifierName) throws Exception {
        ParamSet bespokeClassifierParameters = bespokeClassifierParametersMap
            .getOrDefault(classifierName, new ParamSet());
        ParamSet paramSet = new ParamSet();
        paramSet.addAll(bespokeClassifierParameters);
        paramSet.addAll(universalClassifierParameters);
        return paramSet;
    }

    /**
     * // todo break this into a method of splitting, i.e. resamples / cv - what about using an evaluator? //  Just set
     * it from factory methods in params above. This currently has a problem as seed 0 doesn't //  resample to the same
     * as the offline file split
     */
    public void runExperiments() {
        logger.info("experiments config: " + this);
        ExecutorService executor = buildExecutor();
        List<Exception> exceptions = new ArrayList<>();
        for(final int seed : seeds) {
            for(final String datasetName : datasetNames) {
                Instances[] data = null;
                try {
                    data = loadData(datasetName, seed);
                } catch(Exception e) {
                    logger.severe(e.toString());
                    continue;
                }
                final Instances trainData = data[0];
                final Instances testData = data[1];
                for(final String classifierName : classifierNames) {
                    try {
                        final ClassifierBuilder<? extends Classifier> classifierBuilder = classifierBuilderFactory
                            .getClassifierBuilderByName(classifierName);
                        if(classifierBuilder == null) {
                            logger.severe("no classifier by the name of {" + classifierName + "}, skipping experiment");
                            continue;
                        }
                        final Classifier classifier = classifierBuilder.build();

                        if(classifier instanceof Loggable) {
                            ((Loggable) classifier).getLogger().setLevel(getLogger().getLevel());
                        }
                        final Experiment experiment = new Experiment(trainData, testData, classifier, seed,
                            classifierName, datasetName);
                        executor.submit(() -> {
                            try {
                                runMultipleTrainAndTestExperimentBatch(experiment);
                            } catch(Exception e) {
                                e.printStackTrace();
                            }
                        });
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        executor.shutdown();
    }

    private void applyTrainTimeContract(Experiment experiment, TimeAmount trainTimeContract) {
        // setup the next train contract
        String classifierName = experiment.getClassifierName();
        if(trainTimeContract == null) {
            // no train contract
            logger.info(
                "no train time contract for {" + experiment.getClassifierName() + "} on {" + experiment.getDatasetName()
                    + "}");
            // todo set train contract disabled somehow? Tony set some boolean in the api somewhere, see if
            //  that'll do
        } else {
            logger.info(
                "train time contract of {" + trainTimeContract + "} for {" + experiment.getClassifierName() + "} on {"
                    + experiment.getDatasetName() + "}");
            experiment.setTrainTimeLimit(trainTimeContract.getAmount(), trainTimeContract.getUnit()); // todo add this to
            // the interface, overload
            if(appendTrainTimeContract) {
                experiment
                    .setClassifierName(classifierName + "_" + trainTimeContract.toString().replaceAll(" ", "_"));
            }
        }
    }

    private void applyTrainMemoryContract(Experiment experiment, MemoryAmount trainMemoryContract) {
        // setup the next train contract
        String classifierName = experiment.getClassifierName();
        if(trainMemoryContract == null) {
            // no train contract
            logger.info(
                "no train memory contract for {" + experiment.getClassifierName() + "} on {" + experiment.getDatasetName()
                    + "}");
        } else {
            logger.info(
                "train memory contract of {" + trainMemoryContract + "} for {" + experiment.getClassifierName() + "} "
                    + "on {"
                    + experiment.getDatasetName() + "}");
//            experiment.setMemoryLimit(trainMemoryContract.getAmount(), trainMemoryContract.getUnit()); // todo add this to
            // the interface, overload
            if(appendTrainMemoryContract) {
                experiment
                    .setClassifierName(classifierName + "_" + trainMemoryContract.toString().replaceAll(" ", "_"));
            }
        }
    }

    private void applyTestTimeContract(Experiment experiment, TimeAmount testTimeContract) {
        // setup the next train contract
        String classifierName = experiment.getClassifierName();
        if(testTimeContract == null) {
            // no train contract
            logger.info(
                "no test time contract for {" + experiment.getClassifierName() + "} on {" + experiment.getDatasetName()
                    + "}");
        } else {
            logger.info(
                "test time contract of {" + testTimeContract + "} for {" + experiment.getClassifierName() + "} "
                    + "on {"
                    + experiment.getDatasetName() + "}");
            experiment.setTestTimeLimit(testTimeContract.getUnit(), testTimeContract.getAmount());
            // todo add this to
            // the interface, overload
            if(appendTestTimeContract) {
                experiment
                    .setClassifierName(classifierName + "_" + testTimeContract.toString().replaceAll(" ", "_"));
            }
        }
    }

    private void appendParametersToClassifierName(Experiment experiment) {
        String workingClassifierName = experiment.getClassifierName();
        if(appendClassifierParameters) {
            ParamSet paramSet = experiment.getParamSet();
            String paramSetStr = "_" + paramSet.toString().replaceAll(" ", "_").replaceAll("\"", "#");
            workingClassifierName += paramSetStr;
        }
        experiment.setClassifierName(workingClassifierName);
    }

    // todo build experiment batch class

    // switch to control whether we need to switch out the random source for testing. For example, if we train a
    // classifier for 5 mins, then test, then train for another 5 mins (to 10 mins), then test, the results are
    // different to training for 10 minutes alone then testing. This is because the classifier sources random
    // numbers during testing and training, therefore the extra testing in the first version causes different
    // random numbers. Obviously this only matters if the classifier uses the random source during testing, but
    // for safety it is best to assume all classifiers do and switch the source to an alternate source for each
    // test batch.

    private void trainExperiment(Experiment experiment) throws Exception {
        if(isOverwriteTrain() && isOverwritePredict()) {
            // todo
            //                getLogger().info("results exist");
            //                continue;
        }
        // train classifier
        experiment.train();
        // write train results if enabled
        if(experiment.isEstimateTrain() && !isOverwriteTrain()) {
            final String trainResultsFilePath =
                buildClassifierResultsDirPath(experiment) + "trainFold" + experiment.getSeed() +
                    ".csv";
            final ClassifierResults trainResults = experiment.getTrainResults();
            FileUtils.writeToFile(trainResults.writeFullResultsToString(), trainResultsFilePath);
        }
    }

    private void runMultipleTrainAndTestExperimentBatch(Experiment experiment) throws Exception {
        appendParametersToClassifierName(experiment); // todo move to experiment?
        // for each train contract (pair of strs, one for the amount, one for the unit)
        for(MemoryAmount trainMemoryContract : trainMemoryContracts) {
            applyTrainMemoryContract(experiment, trainMemoryContract);
            for(TimeAmount trainTimeContract : trainTimeContracts) {
                applyTrainTimeContract(experiment, trainTimeContract);
                trainExperiment(experiment);
                runMultipleTestExperimentBatch(experiment);
                // shallow copy experiment so we can reuse the configuration under the next train contract
                experiment = (Experiment) experiment.shallowCopy();
            }
        }
    }

    private void runMultipleTestExperimentBatch(Experiment experiment) throws Exception {
        for(TimeAmount testTimeContract : testTimeContracts) {
            applyTestTimeContract(experiment, testTimeContract);
            testExperiment(experiment);
        }
    }

    private void testExperiment(Experiment experiment) throws Exception {
        // test classifier
        experiment.test();
        // write test results
        if(!isOverwritePredict()) { // todo check if file is there
            final String testResultsFilePath =
                buildClassifierResultsDirPath(experiment) + "trainFold" + experiment.getSeed() + ".csv";
            final ClassifierResults testResults = experiment.getTestResults();
            FileUtils.writeToFile(testResults.writeFullResultsToString(), testResultsFilePath);
        }
    }

    private String buildClassifierResultsDirPath(Experiment experiment) {
        final String classifierResultsDirPath =
            resultsDirPath + "/" + experiment.getClassifierName() + "/" + experiment.getDatasetName() + "/";
        return classifierResultsDirPath;
    }

    public List<String> getClassifierNames() {
        return classifierNames;
    }

    public void setClassifierNames(final List<String> classifierNames) {
        this.classifierNames = classifierNames;
    }

    public List<String> getDatasetDirPaths() {
        return datasetDirPaths;
    }

    public void setDatasetDirPaths(final List<String> datasetDirPaths) {
        this.datasetDirPaths = datasetDirPaths;
    }

    public List<String> getDatasetNames() {
        return datasetNames;
    }

    public void setDatasetNames(final List<String> datasetNames) {
        this.datasetNames = datasetNames;
    }

    public List<Integer> getSeeds() {
        return seeds;
    }

    public void setSeeds(final List<Integer> seeds) {
        this.seeds = seeds;
    }

    public String getResultsDirPath() {
        return resultsDirPath;
    }

    public void setResultsDirPath(final String resultsDirPath) {
        this.resultsDirPath = resultsDirPath;
    }

    public List<String> getTrainTimeContractStrs() {
        return trainTimeContractStrs;
    }

    public void setTrainTimeContractStrs(final List<String> trainTimeContractStrs) {
        this.trainTimeContractStrs = trainTimeContractStrs;
    }

    public List<TimeAmount> getTrainTimeContracts() {
        return trainTimeContracts;
    }

    public void setTrainTimeContracts(final List<TimeAmount> trainTimeContracts) {
        this.trainTimeContracts = trainTimeContracts;
    }

    public boolean isCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(final boolean checkpoint) {
        this.checkpoint = checkpoint;
    }

    public int getNumThreads() {
        return numThreads;
    }

    public void setNumThreads(final int numThreads) {
        this.numThreads = numThreads;
    }

    public boolean isAppendTrainTimeContract() {
        return appendTrainTimeContract;
    }

    public void setAppendTrainTimeContract(final boolean appendTrainTimeContract) {
        this.appendTrainTimeContract = appendTrainTimeContract;
    }

    public boolean isEstimateTrainError() {
        return estimateTrainError;
    }

    public void setEstimateTrainError(final boolean estimateTrainError) {
        this.estimateTrainError = estimateTrainError;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(final String logLevel) {
        this.logLevel = logLevel;
    }

    public Logger getLogger() {
        return logger;
    }

    public ClassifierBuilderFactory<Classifier> getClassifierBuilderFactory() {
        return classifierBuilderFactory;
    }

    public void setClassifierBuilderFactory(
        final ClassifierBuilderFactory<Classifier> classifierBuilderFactory) {
        this.classifierBuilderFactory = classifierBuilderFactory;
    }

    public boolean isOverwriteTrain() {
        return overwriteTrain;
    }

    public void setOverwriteTrain(final boolean overwriteTrain) {
        this.overwriteTrain = overwriteTrain;
    }

    public boolean isOverwritePredict() {
        return overwritePredict;
    }

    public void setOverwritePredict(final boolean overwritePredict) {
        this.overwritePredict = overwritePredict;
    }

    public List<String> getTrainMemoryContractStrs() {
        return trainMemoryContractStrs;
    }

    public void setTrainMemoryContractStrs(final List<String> trainMemoryContractStrs) {
        this.trainMemoryContractStrs = trainMemoryContractStrs;
    }

    public List<MemoryAmount> getTrainMemoryContracts() {
        return trainMemoryContracts;
    }

    public void setTrainMemoryContracts(
        final List<MemoryAmount> trainMemoryContracts) {
        this.trainMemoryContracts = trainMemoryContracts;
    }

    public List<String> getTestTimeContractStrs() {
        return testTimeContractStrs;
    }

    public void setTestTimeContractStrs(final List<String> testTimeContractStrs) {
        this.testTimeContractStrs = testTimeContractStrs;
    }

    public List<TimeAmount> getTestTimeContracts() {
        return testTimeContracts;
    }

    public void setTestTimeContracts(
        final List<TimeAmount> testTimeContracts) {
        this.testTimeContracts = testTimeContracts;
    }

    public boolean isAppendTrainMemoryContract() {
        return appendTrainMemoryContract;
    }

    public void setAppendTrainMemoryContract(final boolean appendTrainMemoryContract) {
        this.appendTrainMemoryContract = appendTrainMemoryContract;
    }

    public boolean isAppendTestTimeContract() {
        return appendTestTimeContract;
    }

    public void setAppendTestTimeContract(final boolean appendTestTimeContract) {
        this.appendTestTimeContract = appendTestTimeContract;
    }

    public static class Runner {

        public static void main(String[] args) throws Exception { // todo abide by unix cmdline convertion
            Main.main(
                "--threads", "1"
                , "-r", "results"
                , "-s", "0"
                , "-c", "DTW_1NN_V1"
                , "-e"
                , "-d", "GunPoint"
                , "--dd", "/bench/datasets"
                , "-p", "DTW_1NN_V1", "-k", "3"
                //                , "-p", "DTW_1NN_V1","-e","false"
            );
        }
    }
}
