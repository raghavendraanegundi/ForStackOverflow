package features;

import cucumber.api.CucumberOptions;
import cucumber.api.event.TestRunFinished;
import cucumber.api.formatter.Formatter;
import cucumber.runtime.ClassFinder;
import cucumber.runtime.Runtime;
import cucumber.runtime.RuntimeOptions;
import cucumber.runtime.RuntimeOptionsFactory;
import cucumber.runtime.io.MultiLoader;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.ResourceLoaderClassFinder;
import cucumber.runtime.junit.Assertions;
import cucumber.runtime.junit.FeatureRunner;
import cucumber.runtime.junit.JUnitOptions;
import cucumber.runtime.junit.JUnitReporter;
import cucumber.runtime.model.CucumberFeature;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * <p>
 * Classes annotated with {@code @RunWith(Cucumber.class)} will run a Cucumber Feature.
 * In general, the runner class should be empty without any fields or methods.
 * For example:
 * <blockquote><pre>
 * &#64;RunWith(Cucumber.class)
 * &#64;CucumberOptions(plugin = "pretty")
 * public class RunCukesTest {
 * }
 * </pre></blockquote>
 * <p>
 * Cucumber will look for a {@code .feature} file on the classpath, using the same resource
 * path as the annotated class ({@code .class} substituted by {@code .feature}).
 * <p>
 * Additional hints can be given to Cucumber by annotating the class with {@link CucumberOptions}.
 * <p>
 * Cucumber also supports JUnits {@link ClassRule}, {@link BeforeClass} and {@link AfterClass} annotations.
 * These will be executed before and after all scenarios. Using these is not recommended as it limits the portability
 * between different runners; they may not execute correctly when using the commandline, IntelliJ IDEA or
 * Cucumber-Eclipse. Instead it is recommended to use Cucumbers `Before` and `After` hooks.
 *
 * @see CucumberOptions
 */
public class DynamicCukeRunner extends ParentRunner<FeatureRunner> {
    private final JUnitReporter jUnitReporter;
    private final List<FeatureRunner> children = new ArrayList<FeatureRunner>();
    private final Runtime runtime;
    private final Formatter formatter;
    private Properties props;


    private static final String STEPS_PACKAGE_KEY = "steps.package";
    private static final String TAGS_TO_RUN_KEY = "tags";
    private static final String CUCUMBER_OPTIONS_KEY = "cucumber.options";
    private static final String FEATURES_DIRECTORY_KEY = "features.directory";
    private static final String DEFAULT_CUKERUNNER_PROPERTIES = "cukerunner.properties";
    private static final String CUKERUNNER_LOCATION_KEY = "cukerunner.properties";
    private static final String CUCUMBER_REPORT_DIRECTORY_KEY = "cucumber.report.directory";
    private static final String DEFAULT_CUCUMBER_REPORT_DIRECTORY = "target/report/cucumber";
    private static final String DEFAULT_TAG = "@web";

    /**
     * Constructor called by JUnit.
     *
     * @param clazz the class with the @RunWith annotation.
     * @throws java.io.IOException                         if there is a problem
     * @throws org.junit.runners.model.InitializationError if there is another problem
     */
    public DynamicCukeRunner(Class clazz) throws InitializationError, IOException {
        super(clazz);
        String cucumberOptions =
                "--tags "
                        + getProperty(TAGS_TO_RUN_KEY, DEFAULT_TAG)
                        + " --glue "
                        + getProperty(STEPS_PACKAGE_KEY)
                        + " --plugin pretty --plugin html:report --plugin json:"
                        + getProperty(CUCUMBER_REPORT_DIRECTORY_KEY, DEFAULT_CUCUMBER_REPORT_DIRECTORY)
                        + "/cucumber.json"
                        + " "
                        + getProperty(FEATURES_DIRECTORY_KEY);
        System.out.println("Setting cucumber options "+CUCUMBER_OPTIONS_KEY+" to "+cucumberOptions);
        System.setProperty(CUCUMBER_OPTIONS_KEY, cucumberOptions);
        Assertions.assertNoCucumberAnnotatedMethods(clazz);

        ClassLoader classLoader = clazz.getClassLoader();
        Assertions.assertNoCucumberAnnotatedMethods(clazz);

        RuntimeOptionsFactory runtimeOptionsFactory = new RuntimeOptionsFactory(clazz);
        RuntimeOptions runtimeOptions = runtimeOptionsFactory.create();

        ResourceLoader resourceLoader = new MultiLoader(classLoader);
        ClassFinder classFinder = new ResourceLoaderClassFinder(resourceLoader, classLoader);
        runtime = new Runtime(resourceLoader, classFinder, classLoader, runtimeOptions);
        formatter = runtimeOptions.formatter(classLoader);
        final JUnitOptions junitOptions = new JUnitOptions(runtimeOptions.getJunitOptions());
        final List<CucumberFeature> cucumberFeatures = runtimeOptions.cucumberFeatures(resourceLoader, runtime.getEventBus());
        jUnitReporter = new JUnitReporter(runtime.getEventBus(), runtimeOptions.isStrict(), junitOptions);
        addChildren(cucumberFeatures);
    }

    @Override
    public List<FeatureRunner> getChildren() {
        return children;
    }

    @Override
    protected Description describeChild(FeatureRunner child) {
        return child.getDescription();
    }

    @Override
    protected void runChild(FeatureRunner child, RunNotifier notifier) {
        child.run(notifier);
    }

    @Override
    protected Statement childrenInvoker(RunNotifier notifier) {
        final Statement features = super.childrenInvoker(notifier);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                features.evaluate();
                runtime.getEventBus().send(new TestRunFinished(runtime.getEventBus().getTime()));
            }
        };
    }

    private void addChildren(List<CucumberFeature> cucumberFeatures) throws InitializationError {
        for (CucumberFeature cucumberFeature : cucumberFeatures) {
            FeatureRunner featureRunner = new FeatureRunner(cucumberFeature, runtime, jUnitReporter);
            if (!featureRunner.isEmpty()) {
                children.add(featureRunner);
            }
        }
    }

    private void initProperties() throws FileNotFoundException {
        if (props == null) {
            props = new Properties();
            loadProperties();
        }
    }

    private String getProperty(String key, String defaultValue) throws FileNotFoundException {
        initProperties();
        String value = props.getProperty(key);
        if (value != null && !value.isEmpty()) {
            System.out.println("Reading property "+key+","+value);
            return value;
        }
        System.out.println("Property "+key+" not set in cukerunner.properties. Using default value: "+defaultValue);
        return defaultValue;
    }

    private String getProperty(String key) throws FileNotFoundException {
        initProperties();
        String value = props.getProperty(key);
        if (value != null && !value.isEmpty()) {
            System.out.println("Reading property "+key+" = "+value);
            return value;
        }
        throw new RuntimeException("Mandatory property " + key + " not set in cukerunner.properties.");
    }

    private void loadProperties() throws FileNotFoundException {
        String propertiesLocation="";
        propertiesLocation = System.getProperty(CUKERUNNER_LOCATION_KEY);

        try {

            if (propertiesLocation != null) {
                System.out.println("Loading CUKERUNNER properties from "+propertiesLocation);
                props.load(new FileInputStream(propertiesLocation));
            } else {
                propertiesLocation = DEFAULT_CUKERUNNER_PROPERTIES;
                System.out.println("Loading CukeRunner properties from classpath"+ propertiesLocation);
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(propertiesLocation);
            props.load(inputStream);

            }
        } catch (NullPointerException | IOException e) {
            e.printStackTrace();
            System.out.println("Error loading settings from "+propertiesLocation);
        }
    }
}
