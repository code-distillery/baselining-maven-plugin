package net.distilledcode.maven.baselining;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.apache.maven.it.util.ResourceExtractor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static java.util.Arrays.asList;

public class DummyApiTest {

    private static final String GROUP_ID = "net.distilledcode.maven.baselining-maven-plugin.it";

    private static Verifier baseVerifier;

    @BeforeClass
    public static void setupClass() {
        try {
            baseVerifier = createVerifier("dummy-1.0.0");
            baseVerifier.executeGoal("install");
            baseVerifier.verifyErrorFreeLog();
            baseVerifier.verifyTextInLog("No baseline version found.");
        } catch (VerificationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void tearDownClass() {
        try {
            baseVerifier.deleteArtifacts(GROUP_ID);
            baseVerifier = null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void noApiChanges() throws IOException, VerificationException {
        final Verifier verifier = createVerifier("dummy-1.0.1-SNAPSHOT");
        verifier.executeGoal("package");
        verifier.verifyErrorFreeLog();
        verifier.verifyTextInLog("Baselining against artifact version 1.0.0");
    }

    @Test
    public void methodAdded() throws IOException, VerificationException {
        final Verifier verifier0 = createVerifier("dummy-1.0.1-SNAPSHOT");
        verifier0.executeGoal("install");
        final Verifier verifier = createVerifier("dummy-1.0.2");
        try {
            verifier.executeGoal("package");
        } catch (VerificationException e) {
            // expected
        }
        verifier.verifyTextInLog("Baselining against artifact version 1.0.0");
        verifier.verifyTextInLog("Suggested export for package: dummy;version=\"1.1.0\"");
        verifier.verifyTextInLog("There were API changes, some package exports need to be adjusted");
        verifier.verifyTextInLog("BUILD FAILURE");

        verifier.deleteArtifacts(GROUP_ID, "dummy-1.0.1-SNAPSHOT", "1.0.1-SNAPSHOT");
    }

    private static Verifier createVerifier(final String testFolderName) throws IOException, VerificationException {
        final File testDir = ResourceExtractor.simpleExtractResources(DummyApiTest.class, "/" + testFolderName);
        final File settingsXml = new File(testDir.getParent(), "settings.xml");
        final Verifier verifier= new Verifier(testDir.getAbsolutePath(), settingsXml.getAbsolutePath());
        verifier.setCliOptions(asList("-s", settingsXml.getAbsolutePath()));
        return verifier;
    }
}