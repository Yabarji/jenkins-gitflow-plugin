package org.jenkinsci.plugins.gitflow.it.action;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitflow.GitflowBuildWrapper;
import org.jenkinsci.plugins.gitflow.data.GitflowPluginData;
import org.jenkinsci.plugins.gitflow.data.RemoteBranch;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlRadioButtonInput;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.maven.MavenModuleSet;
import hudson.model.Result;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitSCM;

/**
 * @author Hannes Osius, Silpion IT-Solutions GmbH  osius@silpion.de
 */
public class TestHotfixIT {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MavenModuleSet mavenProject;

    @Before
    public void setUp() throws Exception {
        j.configureDefaultMaven();
        mavenProject = j.createMavenProject();
        mavenProject.getBuildWrappersList().add(new GitflowBuildWrapper());
    }

    @Test
    public void testTestHotfix() throws Exception {

        File gitRepro = folder.newFolder("testrepo.git");
        this.setUpGitRepo("../testrepo.git_started-hotfix-1.0.zip", gitRepro);

        //make a build before
        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        GitflowPluginData data = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        addRemoteBranch(data, "hotfix/1.0", Result.SUCCESS, "1.0.2-SNAPSHOT");

        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo(mavenProject.getUrl() + "gitflow");

        HtmlForm form = page.getFormByName("performGitflowRelease");
        checkRadioButton("action", "testHotfix_1.0", page);

        j.submit(form);
        j.waitUntilNoActivity();
        mavenProject.getLastBuild().getLogText().writeLogTo(0, System.out);
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        GitflowPluginData data1 = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        assertThat(data1.getRemoteBranch("hotfix/1.0").getLastBuildVersion(), is("1.0.3-SNAPSHOT"));

        //check the Git-Repro
        File repository = folder.newFolder();
        GitClient gitClient = Git.with(j.createTaskListener(), new EnvVars()).in(repository).getClient();
        gitClient.clone(gitRepro.getAbsolutePath(), "origin", false, null);
        Map<String, Branch> branches = getAllBranches(gitClient);

        assertThat(branches.keySet(), containsInAnyOrder("origin/hotfix/1.0", "origin/master", "origin/develop", "master"));

        gitClient.checkoutBranch("hotfix/foobar3", branches.get("origin/hotfix/1.0").getSHA1String());
        this.checkMultiModuleProject(repository, "1.0.3-SNAPSHOT", 4);
    }

    @Test
    public void testTestHotfixDryRun() throws Exception {

        File gitRepro = folder.newFolder("testrepo.git");
        this.setUpGitRepo("../testrepo.git_started-hotfix-1.0.zip", gitRepro);

        //make a build before
        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        GitflowPluginData data = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        addRemoteBranch(data, "hotfix/1.0", Result.SUCCESS, "1.0.2-SNAPSHOT");

        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo(mavenProject.getUrl() + "gitflow");

        HtmlForm form = page.getFormByName("performGitflowRelease");
        checkRadioButton("action", "testHotfix_1.0", page);

        j.submit(form);
        j.waitUntilNoActivity();
        mavenProject.getLastBuild().getLogText().writeLogTo(0, System.out);
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        GitflowPluginData data1 = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        assertThat(data1.getRemoteBranch("hotfix/1.0").getLastBuildVersion(), is("1.0.3-SNAPSHOT"));
        assertThat(data1.getRemoteBranch("hotfix/1.0").getLastBuildResult(), is(Result.SUCCESS));

        //check the Git-Repro
        File repository = folder.newFolder();
        GitClient gitClient = Git.with(j.createTaskListener(), new EnvVars()).in(repository).getClient();
        gitClient.clone(gitRepro.getAbsolutePath(), "origin", false, null);
        Map<String, Branch> branches = getAllBranches(gitClient);

        assertThat(branches.keySet(), containsInAnyOrder("origin/hotfix/1.0", "origin/master", "origin/develop", "master"));

        gitClient.checkoutBranch("hotfix/foobar3", branches.get("origin/hotfix/1.0").getSHA1String());
        checkMultiModuleProject(repository, "1.0.3-SNAPSHOT", 4);
    }

    @Test
    public void testTestHotfixFail() throws Exception {

        File gitRepro = folder.newFolder("testrepo.git");
        this.setUpGitRepo("../testrepo.git_started-hotfix-1.0.zip", gitRepro);

        //make a build before
        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        GitflowPluginData data = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        addRemoteBranch(data, "hotfix/foobar3", Result.SUCCESS, "1.1-SNAPSHOT");

        mavenProject.scheduleBuild2(0).get();
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.SUCCESS));

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo(mavenProject.getUrl() + "gitflow");

        HtmlForm form = page.getFormByName("performGitflowRelease");
        checkRadioButton("action", "testHotfix_1.0", page);

        mavenProject.setGoals("fail");
        j.submit(form);
        j.waitUntilNoActivity();
        mavenProject.getLastBuild().getLogText().writeLogTo(0, System.out);
        assertThat("TestBuild failed", mavenProject.getLastBuild().getResult(), is(Result.FAILURE));

        GitflowPluginData data1 = mavenProject.getLastBuild().getAction(GitflowPluginData.class);
        assertThat(data1.getRemoteBranch("hotfix/1.0").getLastBuildVersion(), is("1.0.2-SNAPSHOT"));
        assertThat(data1.getRemoteBranch("hotfix/1.0").getLastBuildResult(), is(Result.FAILURE));

        //check the Git-Repro
        File repository = folder.newFolder();
        GitClient gitClient = Git.with(j.createTaskListener(), new EnvVars()).in(repository).getClient();
        gitClient.clone(gitRepro.getAbsolutePath(), "origin", false, null);
        Map<String, Branch> branches = getAllBranches(gitClient);

        assertThat(branches.keySet(), containsInAnyOrder("origin/hotfix/1.0", "origin/master", "origin/develop", "master"));

        gitClient.checkoutBranch("hotfix/foobar3", branches.get("origin/hotfix/1.0").getSHA1String());
        checkMultiModuleProject(repository, "1.0.2-SNAPSHOT", 4);
    }

    /**
     * Find a RadionButton an set it to checked.
     *
     * @param radioButtonGroup the name of the RadioButton Groupe.
     * @param buttonName the name of the button to Check
     * @param page the Page with the Button on it
     */
    private void checkRadioButton(String radioButtonGroup, String buttonName, HtmlPage page) {
        List<HtmlElement> elementList = page.getElementsByName(radioButtonGroup);
        for (HtmlElement htmlElement : elementList) {
            if (htmlElement instanceof HtmlRadioButtonInput) {
                HtmlRadioButtonInput radioButtonInput = (HtmlRadioButtonInput) htmlElement;
                String value = radioButtonInput.getAttributesMap().get("value").getValue();
                if (buttonName.equals(value)) {
                    radioButtonInput.setChecked(true);
                    break;
                }
            }
        }

    }
    /**
     * get all Branches for the Repo as Map.
     * <p/>
     * the key is the branchname and the value is the branch.
     *
     * @param gitClient the gitClient
     * @return the Map of Branches.
     * @throws InterruptedException
     */
    private Map<String, Branch> getAllBranches(GitClient gitClient) throws InterruptedException {
        Map<String, Branch> map = new HashMap<String, Branch>();
        for (Branch branch : gitClient.getBranches()) {
            map.put(branch.getName(), branch);
        }
        return map;
    }

    /**
     * Check the Version of a multi module Maven Project.
     *
     * @param rootDir the root of the Maven Project.
     * @param version the version to check.
     * @param pomCount the number of poms to check.
     * @throws IOException
     * @throws InterruptedException
     * @throws XmlPullParserException
     */
    private void checkMultiModuleProject(File rootDir, final String version, int pomCount) throws IOException, InterruptedException, XmlPullParserException {
        MavenXpp3Reader mavenreader = new MavenXpp3Reader();
        Iterator iterator = FileUtils.iterateFiles(rootDir, new String[] { "xml" }, true);
        int count = 0;
        while (iterator.hasNext()) {
            File next =  (File) iterator.next();
            if ("pom.xml".compareTo(next.getName())==0){
                count++;
                assertThat("Development Version was not set", mavenreader.read(new FileReader(next)).getVersion(), is(version));
            }
        }
        assertThat("not all Modules was checked",count, is(pomCount));
    }


    /**
     * unzip the give zipFile to the given temp-Folder
     *
     * @param pathToGitZip
     * @param pathToUnpack
     * @throws IOException
     * @throws URISyntaxException
     * @throws InterruptedException
     */
    private void setUpGitRepo(final String pathToGitZip, File pathToUnpack) throws IOException, URISyntaxException, InterruptedException {
        URL resource = getClass().getResource(pathToGitZip);
        FilePath filePath = new FilePath(new File(resource.toURI()));
        filePath.unzip(new FilePath(pathToUnpack));

        GitSCM gitSCM = new GitSCM(pathToUnpack.getAbsolutePath());
        mavenProject.setScm(gitSCM);
    }

    private void addRemoteBranch(GitflowPluginData data, String branch, Result result, String version) {
        RemoteBranch masterBranch = data.getOrAddRemoteBranch(branch);
        masterBranch.setLastBuildVersion(version);
        masterBranch.setLastBuildResult(result);
    }

}


