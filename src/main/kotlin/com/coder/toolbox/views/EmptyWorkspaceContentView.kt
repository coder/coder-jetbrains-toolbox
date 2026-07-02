package com.coder.toolbox.views

import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.remoteDev.environments.CachedIdeStub
import com.jetbrains.toolbox.api.remoteDev.environments.CachedProject
import com.jetbrains.toolbox.api.remoteDev.environments.ManualEnvironmentContentsView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Contents view for environments without a resolved agent (i.e. workspaces that
 * are not running). There is nothing to connect to yet, so it reports empty IDE
 * and project lists instead of an SSH view with no host.
 */
object EmptyWorkspaceContentView : ManualEnvironmentContentsView {
    override val ideListState: Flow<LoadableState<List<CachedIdeStub>>> = flowOf(LoadableState.Value(emptyList()))

    override val projectListState: Flow<LoadableState<List<CachedProject>>> = flowOf(LoadableState.Value(emptyList()))
}