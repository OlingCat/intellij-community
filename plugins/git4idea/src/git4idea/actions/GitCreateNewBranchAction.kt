/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.actions

import com.intellij.dvcs.DvcsUtil.guessVcsRoot
import com.intellij.dvcs.branch.DvcsSyncSettings.Value.SYNC
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.GitUtil.HEAD
import git4idea.GitUtil.getRepositoryManager
import git4idea.branch.GitBranchUtil.getNewBranchNameFromUser
import git4idea.branch.GitBrancher
import git4idea.config.GitVcsSettings
import git4idea.repo.GitRepository

internal class GitCreateNewBranchAction : DumbAwareAction() {

  override fun actionPerformed(e: AnActionEvent) {
    val data = collectData(e)
    when (data) {
      is Data.WithCommit -> createBranchStartingFrom(data.repository, data.hash)
      is Data.NoCommit -> createBranch(data.project, data.repositories)
    }
  }

  override fun update(e: AnActionEvent) {
    super.update(e)
    val data = collectData(e)
    when (data) {
      is Data.Invisible -> e.presentation.isEnabledAndVisible = false
      is Data.Disabled -> {
        e.presentation.isVisible = true
        e.presentation.isEnabled = false
      }
      else -> e.presentation.isEnabledAndVisible = true
    }
  }

  private sealed class Data {
    class Invisible : Data()
    class Disabled : Data()
    class WithCommit(val repository: GitRepository, val hash: Hash) : Data()
    class NoCommit(val project: Project, val repositories: List<GitRepository>) : Data()
  }

  private fun collectData(e: AnActionEvent): Data {
    val project = e.project ?: return Data.Invisible()
    val manager = getRepositoryManager(project)

    val log = e.getData(VcsLogDataKeys.VCS_LOG)
    if (log != null) {
      val commits = log.selectedCommits
      if (commits.isEmpty()) return Data.Invisible()
      if (commits.size > 1) return Data.Disabled()
      val commit = commits.first()
      val repository = manager.getRepositoryForRoot(commit.root)
      if (repository != null) {
        return Data.WithCommit(repository, commit.hash)
      }
    }

    val repositories =
      if (manager.moreThanOneRoot()) {
        if (GitVcsSettings.getInstance(project).syncSetting == SYNC) manager.repositories
        else {
          val repository = manager.getRepositoryForRoot(guessVcsRoot(project, e.getData(VIRTUAL_FILE)))
          repository?.let { listOf(repository) }
        }
      }
      else listOf(manager.repositories.first())

    if (repositories == null || repositories.any { it.isFresh }) return Data.Invisible()
    return Data.NoCommit(project, repositories)
  }

  fun createBranch(project: Project, repositories: List<GitRepository>) {
    val options = getNewBranchNameFromUser(project, repositories, "Create New Branch")
    if (options != null) {
      val brancher = GitBrancher.getInstance(project)
      if (options.checkout) {
        brancher.checkoutNewBranch(options.name, repositories)
      }
      else {
        brancher.createBranch(options.name, repositories.map { it to HEAD }.toMap())
      }
    }
  }

  fun createBranchStartingFrom(repository: GitRepository, commit: Hash) {
    val project = repository.project
    val options = getNewBranchNameFromUser(project, setOf(repository), "Create New Branch From ${commit.toShortString()}")
    if (options != null) {
      val brancher = GitBrancher.getInstance(project)
      if (options.checkout) {
        brancher.checkoutNewBranchStartingFrom(options.name, commit.asString(), listOf(repository), null)
      }
      else {
        brancher.createBranch(options.name, mapOf(repository to commit.asString()))
      }
    }
  }
}
