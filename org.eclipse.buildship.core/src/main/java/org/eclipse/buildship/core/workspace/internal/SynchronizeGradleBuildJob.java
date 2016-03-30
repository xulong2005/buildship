/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.core.workspace.internal;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import com.gradleware.tooling.toolingmodel.OmniEclipseGradleBuild;
import com.gradleware.tooling.toolingmodel.repository.FetchStrategy;
import com.gradleware.tooling.toolingmodel.repository.FixedRequestAttributes;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.util.progress.AsyncHandler;
import org.eclipse.buildship.core.util.progress.ToolingApiWorkspaceJob;
import org.eclipse.buildship.core.workspace.ModelProvider;
import org.eclipse.buildship.core.workspace.NewProjectHandler;

/**
 * Forces the reload of the given Gradle build and synchronizes it with the Eclipse workspace.
 */
public class SynchronizeGradleBuildJob extends ToolingApiWorkspaceJob {

    private final FixedRequestAttributes rootRequestAttributes;
    private final NewProjectHandler newProjectHandler;
    private final AsyncHandler initializer;

    public SynchronizeGradleBuildJob(FixedRequestAttributes rootRequestAttributes, NewProjectHandler newProjectHandler, AsyncHandler initializer) {
        this(rootRequestAttributes, newProjectHandler, initializer, false);
    }

    public SynchronizeGradleBuildJob(FixedRequestAttributes rootRequestAttributes, NewProjectHandler newProjectHandler, AsyncHandler initializer, boolean showUserNotifications) {
        super(String.format("Synchronize Gradle build at %s with workspace", Preconditions.checkNotNull(rootRequestAttributes).getProjectDir().getAbsolutePath()), showUserNotifications);

        this.rootRequestAttributes = Preconditions.checkNotNull(rootRequestAttributes);
        this.newProjectHandler = Preconditions.checkNotNull(newProjectHandler);
        this.initializer = Preconditions.checkNotNull(initializer);

        // explicitly show a dialog with the progress while the project synchronization is in process
        setUser(true);

        // guarantee sequential order of synchronize jobs
        setRule(ResourcesPlugin.getWorkspace().getRoot());
    }

    @Override
    protected void runToolingApiJobInWorkspace(IProgressMonitor monitor) throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 10);

        this.initializer.run(progress.newChild(1), getToken());
        ModelProvider modelProvider = CorePlugin.gradleWorkspaceManager().getGradleBuild(this.rootRequestAttributes).getModelProvider();
        OmniEclipseGradleBuild gradleBuild = modelProvider.fetchEclipseGradleBuild(FetchStrategy.FORCE_RELOAD, progress.newChild(4), getToken());
        new SynchronizeGradleBuildOperation(gradleBuild, this.rootRequestAttributes, this.newProjectHandler).run(progress.newChild(5));
    }

    /**
     * A {@link SynchronizeGradleBuildJob} is only scheduled if there is not already another one that fully covers it.
     * <p/>
     * A job A fully covers a job B if all of these conditions are met:
     * <ul>
     *  <li> A synchronizes the same Gradle build as B </li>
     *  <li> A and B have the same {@link AsyncHandler} or B's {@link AsyncHandler} is a no-op </li>
     *  <li> A and B have the same {@link NewProjectHandler} or B's {@link NewProjectHandler} is a no-op </li>
     * </ul>
     */
    @Override
    public boolean shouldSchedule() {
        for (Job job : Job.getJobManager().find(CorePlugin.GRADLE_JOB_FAMILY)) {
            if (job instanceof SynchronizeGradleBuildJob && isCoveredBy((SynchronizeGradleBuildJob) job)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCoveredBy(SynchronizeGradleBuildJob other) {
        return Objects.equal(this.rootRequestAttributes, other.rootRequestAttributes)
            && (this.newProjectHandler == NewProjectHandler.NO_OP || Objects.equal(this.newProjectHandler, other.newProjectHandler))
            && (this.initializer == AsyncHandler.NO_OP || Objects.equal(this.initializer, other.initializer));
    }

}
