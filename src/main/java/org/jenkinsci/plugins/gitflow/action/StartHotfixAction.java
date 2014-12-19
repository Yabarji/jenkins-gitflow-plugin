package org.jenkinsci.plugins.gitflow.action;

import static hudson.model.Result.SUCCESS;
import static org.jenkinsci.plugins.gitflow.GitflowBuildWrapper.getGitflowBuildWrapperDescriptor;

import java.io.IOException;

import org.jenkinsci.plugins.gitflow.cause.StartHotfixCause;
import org.jenkinsci.plugins.gitflow.data.RemoteBranch;
import org.jenkinsci.plugins.gitflow.proxy.gitclient.GitClientProxy;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

/**
 * This class executes the required steps for the Gitflow action <i>Start Hotfix</i>.
 *
 * @param <B> the build in progress.
 * @author Hannes Osius, Silpion IT-Solutions GmbH - osius@silpion.de
 */
public class StartHotfixAction<B extends AbstractBuild<?, ?>> extends AbstractGitflowAction<B, StartHotfixCause> {

    private static final String ACTION_NAME = "Start Hotfix";

    private static final String MSG_PATTERN_UPDATED_NEXT_PATCH_DEVELOPMENT_VERSION = "Gitflow - %s: Updated project files to next patch development version %s%n";

    /**
     * Initialises a new <i>Start Hotfix</i> action.
     *
     * @param build the <i>Start Hotfix</i> build that is in progress.
     * @param launcher can be used to launch processes for this build - even if the build runs remotely.
     * @param listener can be used to send any message.
     * @param git the Git client used to execute commands for the Gitflow actions.
     * @param startHotfixCause the cause for the new action.
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    public <BC extends B> StartHotfixAction(BC build, Launcher launcher, BuildListener listener, GitClientProxy git, StartHotfixCause startHotfixCause) throws IOException, InterruptedException {
        super(build, launcher, listener, git, startHotfixCause);
    }

    /** {@inheritDoc} */
    @Override
    protected String getActionName() {
        return ACTION_NAME;
    }

    /** {@inheritDoc} */
    @Override
    protected void beforeMainBuildInternal() throws IOException, InterruptedException {

        // Create a new hotfix branch based on the master branch.
        final String hotfixBranch = getGitflowBuildWrapperDescriptor().getHotfixBranchPrefix() + this.gitflowCause.getHotfixVersion();
        final String masterBranch = getGitflowBuildWrapperDescriptor().getMasterBranch();
        this.createBranch(hotfixBranch, masterBranch);

        // Update the version numbers in the project files to the hotfix version.
        final String nextPatchDevelopmentVersion = this.gitflowCause.getNextPatchDevelopmentVersion();
        this.addFilesToGitStage(this.buildTypeAction.updateVersion(nextPatchDevelopmentVersion));
        final String msgUpadtedReleaseVersion = formatPattern(MSG_PATTERN_UPDATED_NEXT_PATCH_DEVELOPMENT_VERSION, ACTION_NAME, nextPatchDevelopmentVersion);
        this.git.commit(msgUpadtedReleaseVersion);
        this.consoleLogger.print(msgUpadtedReleaseVersion);

        // Push the new hotfix branch to the remote repo.
        this.git.push("origin", "refs/heads/" + hotfixBranch + ":refs/heads/" + hotfixBranch);

        // Record the remote branch data.
        final RemoteBranch remoteBranch = this.gitflowPluginData.getOrAddRemoteBranch(hotfixBranch);
        remoteBranch.setLastBuildResult(SUCCESS);
        remoteBranch.setLastBuildVersion(nextPatchDevelopmentVersion);

        // Add environment and property variables
        this.additionalBuildEnvVars.put("GIT_SIMPLE_BRANCH_NAME", hotfixBranch);
        this.additionalBuildEnvVars.put("GIT_REMOTE_BRANCH_NAME", "origin/" + hotfixBranch);
        this.additionalBuildEnvVars.put("GIT_BRANCH_TYPE", getGitflowBuildWrapperDescriptor().getBranchType(hotfixBranch));

        // There's no need to execute the main build.
        this.buildTypeAction.skipMainBuild(this.additionalBuildEnvVars);
    }

    /** {@inheritDoc} */
    @Override
    protected void afterMainBuildInternal() throws IOException, InterruptedException {
        // Nothing to do.
    }
}
