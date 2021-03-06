package de.silpion.jenkins.plugins.gitflow;

import com.google.common.annotations.VisibleForTesting;
import de.silpion.jenkins.plugins.gitflow.cause.AbstractGitflowCause;
import de.silpion.jenkins.plugins.gitflow.cause.HotfixBranchCauseGroup;
import de.silpion.jenkins.plugins.gitflow.cause.ReleaseBranchCauseGroup;
import de.silpion.jenkins.plugins.gitflow.cause.StartHotfixCause;
import de.silpion.jenkins.plugins.gitflow.cause.StartReleaseCause;
import de.silpion.jenkins.plugins.gitflow.cause.TestHotfixCause;
import de.silpion.jenkins.plugins.gitflow.cause.TestReleaseCause;
import de.silpion.jenkins.plugins.gitflow.data.GitflowPluginData;
import de.silpion.jenkins.plugins.gitflow.data.RemoteBranch;
import de.silpion.jenkins.plugins.gitflow.proxy.gitclient.GitClientProxy;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.PermalinkProjectAction;
import hudson.model.StreamBuildListener;
import hudson.util.NullStream;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The action that appears as link in the side bar of a project. Users will click on it in order to execute a Gitflow action.
 *
 * @author Marc Rohlfs, Silpion IT-Solutions GmbH - rohlfs@silpion.de
 */
public class GitflowProjectAction implements PermalinkProjectAction {

    @VisibleForTesting static final String KEY_ACTION = "action";
    @VisibleForTesting static final String KEY_VALUE = "value";
    @VisibleForTesting static final String KEY_DRY_RUN = "dryRun";

    @VisibleForTesting static final String KEY_PREFIX_START_RELEASE = "startRelease";
    @VisibleForTesting static final String KEY_PREFIX_TEST_RELEASE = "testRelease";
    @VisibleForTesting static final String KEY_PREFIX_PUBLISH_RELEASE = "publishRelease";
    @VisibleForTesting static final String KEY_PREFIX_FINISH_RELEASE = "finishRelease";
    @VisibleForTesting static final String KEY_PREFIX_START_HOTFIX = "startHotfix";
    @VisibleForTesting static final String KEY_PREFIX_TEST_HOTFIX = "testHotfix";
    @VisibleForTesting static final String KEY_PREFIX_PUBLISH_HOTFIX = "publishHotfix";
    @VisibleForTesting static final String KEY_PREFIX_FINISH_HOTFIX = "finishHotfix";

    @VisibleForTesting static final String KEY_POSTFIX_RELEASE_VERSION = "releaseVersion";
    @VisibleForTesting static final String KEY_POSTFIX_HOTFIX_VERSION = "hotfixVersion";
    @VisibleForTesting static final String KEY_POSTFIX_NEXT_RELEASE_DEVELOPMENT_VERSION = "nextReleaseDevelopmentVersion";
    @VisibleForTesting static final String KEY_POSTFIX_NEXT_PATCH_DEVELOPMENT_VERSION = "nextPatchDevelopmentVersion";
    @VisibleForTesting static final String KEY_POSTFIX_PATCH_RELEASE_VERSION = "patchReleaseVersion";

    private static final Comparator<String> VERSION_NUMBER_COMPARATOR = new Comparator<String>() {

        public int compare(final String versionNumber1, final String versionNumber2) {
            int result = 0;

            final String[] versionNumberTokens1 = StringUtils.split(versionNumber1, ".");
            final String[] versionNumberTokens2 = StringUtils.split(versionNumber2, ".");

            for (int i = 0; i < Math.min(versionNumberTokens1.length, versionNumberTokens2.length); i++) {
                result = Integer.compare(Integer.parseInt(versionNumberTokens1[i]), Integer.parseInt(versionNumberTokens2[i]));
                if (result != 0) {
                    break;
                }
            }

            if (result == 0) {
                result = Integer.compare(versionNumberTokens1.length, versionNumberTokens2.length);
            }

            return result;
        }
    };

    private final AbstractProject<?, ?> job;

    private StartReleaseCause startReleaseCause;
    private Map<String, ReleaseBranchCauseGroup> releaseBranchCauseGroupsByVersion = new TreeMap<String, ReleaseBranchCauseGroup>(VERSION_NUMBER_COMPARATOR);

    private StartHotfixCause startHotfixCause;
    private Map<String, HotfixBranchCauseGroup> hotfixBranchCauseGroupsByVersion = new TreeMap<String, HotfixBranchCauseGroup>(VERSION_NUMBER_COMPARATOR);

    /**
     * Initialises a new {@link GitflowProjectAction}.
     *
     * @param job the job/project that the Gitflow actions can be applied to.
     */
    public GitflowProjectAction(final AbstractProject<?, ?> job) {
        this.job = job;

        // Try to get the action object that holds the data for the Gitflow plugin and extract the recorded remote branch information.
        for (AbstractBuild<?, ?> lastBuild = job.getLastBuild(); lastBuild != null; lastBuild = lastBuild.getPreviousBuild()) {
            final GitflowPluginData gitflowPluginData = lastBuild.getAction(GitflowPluginData.class);
            if (gitflowPluginData != null) {

                // The action form should only offer actions on the recorded remote branches that still exist.
                // NOTE that proper error handling for Git client problems is not possible here. That's why the methods
                // 'createGitClient' and 'isExistingBlessedRemoteBranch' swallow exceptions instead of handling them in any way.
                final GitClientProxy git = createGitClient(job);
                for (final RemoteBranch remoteBranch : gitflowPluginData.getRemoteBranches()) {
                    final String branchName = remoteBranch.getBranchName();
                    if (git == null || isExistingBlessedRemoteBranch(git, branchName)) {

                        final String branchType = GitflowBuildWrapper.getGitflowBuildWrapperDescriptor().getBranchType(branchName);
                        if ("develop".equals(branchType)) {
                            this.startReleaseCause = new StartReleaseCause(remoteBranch);
                        } else if ("release".equals(branchType)) {
                            final ReleaseBranchCauseGroup releaseBranchCauseGroup = new ReleaseBranchCauseGroup(remoteBranch);
                            this.releaseBranchCauseGroupsByVersion.put(releaseBranchCauseGroup.getReleaseVersion(), releaseBranchCauseGroup);
                        } else if ("master".equals(branchType) && remoteBranch.getBaseReleaseVersion() != null) {
                            // When the master branch has a snapshot version, we assume an initial commit and not a published release.
                            if (!StringUtils.endsWith(remoteBranch.getLastBuildVersion(), "-SNAPSHOT")) {
                                this.startHotfixCause = new StartHotfixCause(remoteBranch);
                            }
                        } else if ("hotfix".equals(branchType)) {
                            final HotfixBranchCauseGroup hotfixBranchCauseGroup = new HotfixBranchCauseGroup(remoteBranch);
                            this.hotfixBranchCauseGroupsByVersion.put(hotfixBranchCauseGroup.getHotfixVersion(), hotfixBranchCauseGroup);
                        }
                    }
                }

                break;
            }
        }

        // Set startHotfixCause to null when the published release still has a release branch or already has a hotfix branch.
        if (this.startHotfixCause != null) {
            final String hotfixVersion = this.startHotfixCause.getHotfixVersion();
            if (this.releaseBranchCauseGroupsByVersion.containsKey(hotfixVersion) || this.hotfixBranchCauseGroupsByVersion.containsKey(hotfixVersion)) {
                this.startHotfixCause = null;
            }
        }
    }

    private static GitClientProxy createGitClient(final AbstractProject<?, ?> job) {
        GitClientProxy gitClientProxy = null;

        final AbstractBuild<?, ?> lastBuild = job.getLastBuild();
        if (lastBuild != null) {
            try {
                gitClientProxy = new GitClientProxy(lastBuild, new StreamBuildListener(new NullStream()), false);
            } catch (final Exception ignored) {
                // NOTE that proper error handling for Git client problems is not possible here.
                // That's why exceptions are swallowed instead of being handled in any way.
            }
        }

        return gitClientProxy;
    }

    private static boolean isExistingBlessedRemoteBranch(final GitClientProxy git, final String branchName) {
        try {
            return git.getHeadRev(branchName) != null;
        } catch (final Exception ignored) {
            // NOTE that proper error handling for Git client problems is not possible here.
            // That's why exceptions are swallowed instead of being handled in any way.
            return true;
        }
    }

    public List<Permalink> getPermalinks() {
        return Collections.emptyList();
    }

    public String getIconFileName() {
        if (GitflowBuildWrapper.hasReleasePermission(this.job)) {
            return "/plugin/" + this.getUrlName() + "/img/Gitflow-Icon-48x48.png";
        } else {
            return null;
        }
    }

    public String getDisplayName() {
        return "Gitflow";
    }

    public String getUrlName() {
        return "gitflow";
    }

    @SuppressWarnings("UnusedDeclaration")
    public void doSubmit(final StaplerRequest request, final StaplerResponse response) throws IOException, ServletException {

        // Identify the cause object for the selected action and overwrite the fields that can be changed by the user.
        final JSONObject submittedForm = request.getSubmittedForm();
        final JSONObject submittedAction = submittedForm.getJSONObject(KEY_ACTION);
        final String action = submittedAction.getString(KEY_VALUE);
        final AbstractGitflowCause gitflowCause;
        if (KEY_PREFIX_START_RELEASE.equals(action)) {
            this.startReleaseCause.setReleaseVersion(submittedAction.getString(KEY_PREFIX_START_RELEASE + "_" + KEY_POSTFIX_RELEASE_VERSION));
            this.startReleaseCause.setNextPatchDevelopmentVersion(submittedAction.getString(KEY_PREFIX_START_RELEASE + "_" + KEY_POSTFIX_NEXT_PATCH_DEVELOPMENT_VERSION));
            this.startReleaseCause.setNextReleaseDevelopmentVersion(submittedAction.getString(KEY_PREFIX_START_RELEASE + "_" + KEY_POSTFIX_NEXT_RELEASE_DEVELOPMENT_VERSION));
            gitflowCause = this.startReleaseCause;
        } else if (action.startsWith(KEY_PREFIX_TEST_RELEASE)) {
            final ReleaseBranchCauseGroup causeGroup = this.releaseBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_TEST_RELEASE + "_" + KEY_POSTFIX_RELEASE_VERSION));
            final String releaseVersionDotfree = causeGroup.getReleaseVersionDotfree();
            final TestReleaseCause testReleaseCause = causeGroup.getTestReleaseCause();
            testReleaseCause.setPatchReleaseVersion(submittedAction.getString(KEY_PREFIX_TEST_RELEASE + "_" + releaseVersionDotfree + "_" + KEY_POSTFIX_PATCH_RELEASE_VERSION));
            testReleaseCause.setNextPatchDevelopmentVersion(submittedAction.getString(KEY_PREFIX_TEST_RELEASE + "_" + releaseVersionDotfree + "_" + KEY_POSTFIX_NEXT_PATCH_DEVELOPMENT_VERSION));
            gitflowCause = testReleaseCause;
        } else if (action.startsWith(KEY_PREFIX_PUBLISH_RELEASE)) {
            final ReleaseBranchCauseGroup causeGroup = this.releaseBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_PUBLISH_RELEASE + "_" + KEY_POSTFIX_RELEASE_VERSION));
            final String releaseVersionDotfree = causeGroup.getReleaseVersionDotfree();
            gitflowCause = causeGroup.getPublishReleaseCause();
        } else if (action.startsWith(KEY_PREFIX_FINISH_RELEASE)) {
            final ReleaseBranchCauseGroup causeGroup = this.releaseBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_FINISH_RELEASE + "_" + KEY_POSTFIX_RELEASE_VERSION));
            gitflowCause = causeGroup.getFinishReleaseCause();
        } else if (KEY_PREFIX_START_HOTFIX.equals(action)) {
            this.startHotfixCause.setNextPatchDevelopmentVersion(submittedAction.getString(KEY_PREFIX_START_HOTFIX + "_" + KEY_POSTFIX_NEXT_PATCH_DEVELOPMENT_VERSION));
            gitflowCause = this.startHotfixCause;
        } else if (action.startsWith(KEY_PREFIX_TEST_HOTFIX)) {
            final HotfixBranchCauseGroup causeGroup = this.hotfixBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_TEST_HOTFIX + "_" + KEY_POSTFIX_HOTFIX_VERSION));
            final TestHotfixCause testHotfixCause = causeGroup.getTestHotfixCause();
            final String hotfixVersionDotfree = causeGroup.getHotfixVersionDotfree();
            testHotfixCause.setPatchReleaseVersion(submittedAction.getString(KEY_PREFIX_TEST_HOTFIX + "_" + hotfixVersionDotfree + "_" + KEY_POSTFIX_PATCH_RELEASE_VERSION));
            testHotfixCause.setNextPatchDevelopmentVersion(submittedAction.getString(KEY_PREFIX_TEST_HOTFIX + "_" + hotfixVersionDotfree + "_" + KEY_POSTFIX_NEXT_PATCH_DEVELOPMENT_VERSION));
            gitflowCause = testHotfixCause;
        } else if (action.startsWith(KEY_PREFIX_PUBLISH_HOTFIX)) {
            final String hotfixVersion = submittedAction.getString(KEY_PREFIX_PUBLISH_HOTFIX + "_" + KEY_POSTFIX_HOTFIX_VERSION);
            final HotfixBranchCauseGroup causeGroup = this.hotfixBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_PUBLISH_HOTFIX + "_" + KEY_POSTFIX_HOTFIX_VERSION));
            gitflowCause = causeGroup.getPublishHotfixCause();
        } else if (action.startsWith(KEY_PREFIX_FINISH_HOTFIX)) {
            gitflowCause = this.hotfixBranchCauseGroupsByVersion.get(submittedAction.getString(KEY_PREFIX_FINISH_HOTFIX + "_" + KEY_POSTFIX_HOTFIX_VERSION)).getFinishHotfixCause();
        } else {
            // Only an IOException causes the build to fail properly.
            throw new IOException("Unknown Gitflow action " + action);
        }
        gitflowCause.setDryRun(submittedForm.getBoolean(KEY_DRY_RUN));

        // Start a build.
        this.job.scheduleBuild(0, gitflowCause);

        // Return to the main page of the job.
        response.sendRedirect(request.getContextPath() + '/' + this.job.getUrl());
    }

    @SuppressWarnings("UnusedDeclaration")
    public StartReleaseCause getStartReleaseCause() {
        return this.startReleaseCause;
    }

    @SuppressWarnings("UnusedDeclaration")
    public Collection<ReleaseBranchCauseGroup> getReleaseBranchCauseGroups() {
        return this.releaseBranchCauseGroupsByVersion.values();
    }

    @SuppressWarnings("UnusedDeclaration")
    public StartHotfixCause getStartHotfixCause() {
        return this.startHotfixCause;
    }

    @SuppressWarnings("UnusedDeclaration")
    public Collection<HotfixBranchCauseGroup> getHotfixBranchCauseGroups() {
        return this.hotfixBranchCauseGroupsByVersion.values();
    }
}
