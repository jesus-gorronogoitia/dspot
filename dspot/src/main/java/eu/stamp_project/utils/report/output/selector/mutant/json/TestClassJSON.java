package eu.stamp_project.utils.report.output.selector.mutant.json;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Benjamin DANGLOT
 * benjamin.danglot@inria.fr
 * on 2/17/17
 */
public class TestClassJSON implements eu.stamp_project.utils.report.output.selector.TestClassJSON {

    public final int nbMutantKilledOriginally;
    private final String name;
    private final long nbOriginalTestCases;
    private List<TestCaseJSON> testCases;

    public TestClassJSON(int nbMutantKilledOriginally, String name, long nbOriginalTestCases) {
        this.nbMutantKilledOriginally = nbMutantKilledOriginally;
        this.name = name;
        this.nbOriginalTestCases = nbOriginalTestCases;
        this.testCases = new ArrayList<>();
    }

    public boolean addTestCase(TestCaseJSON testCaseJSON) {
        if (this.testCases == null) {
            this.testCases = new ArrayList<>();
        }
        return this.testCases.add(testCaseJSON);
    }

}
