package org.jetbrains.idea.svn.history;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.committed.ChangesBunch;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesCache;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesNavigation;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.idea.svn.SvnVcs;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;

import java.util.*;

public class SvnRevisionsNavigationMediator implements CommittedChangesNavigation {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.history.SvnRevisionsNavigationMediator");

  public final static int CHUNK_SIZE = 50;

  private final InternallyCachedProvider myInternallyCached;
  private final VisuallyCachedProvider myVisuallyCached;
  
  private final List<List<Fragment>> myChunks;
  private boolean myCanNotGoBack;
  private int myCurrentIdx;
  private final BunchFactory myChunkFactory;
  private Project myProject;

  public SvnRevisionsNavigationMediator(final SvnRepositoryLocation location, final Project project, final VirtualFile vcsRoot) throws
                                                                                                                                VcsException {
    myProject = project;
    final SvnVcs vcs = SvnVcs.getInstance(project);

    myChunks = new LinkedList<List<Fragment>>();

    SVNRepository repository = null;
    final SVNURL repositoryRoot;
    final long youngRevision;
    try {
      repository = vcs.createRepository(location.getURL());
      youngRevision = repository.getLatestRevision();
      repositoryRoot = repository.getRepositoryRoot(false);
    }
    catch (SVNException e) {
      throw new VcsException(e);
    }
    finally {
      if (repository != null) {
        repository.closeSession();
      }
    }

    final Iterator<ChangesBunch> visualIterator = project.isDefault() ? null :
        CommittedChangesCache.getInstance(project).getBackBunchedIterator(vcs, vcsRoot, location, CHUNK_SIZE);
    final Iterator<ChangesBunch> internalIterator = project.isDefault() ? null : LoadedRevisionsCache.getInstance(project).iterator(location.getURL());

    myInternallyCached = (internalIterator == null) ? null : new InternallyCachedProvider(internalIterator, myProject);
    myVisuallyCached = (visualIterator == null) ? null : new VisuallyCachedProvider(visualIterator, myProject, location);

    myChunkFactory = new BunchFactory(myInternallyCached, myVisuallyCached,
                                      new LiveProvider(vcs, location, youngRevision, new SvnLogUtil(myProject, vcs, location, repositoryRoot)));

    myCurrentIdx = -1;
    // init first screen
    goBack();
  }

  public boolean canGoBack() {
    return ((myCurrentIdx + 1) < myChunks.size()) || (! myCanNotGoBack);
  }

  public boolean canGoForward() {
    return myCurrentIdx > 0;
  }

  public void goBack() throws VcsException {
    if ((myCurrentIdx + 1) < myChunks.size()) {
      ++ myCurrentIdx;
      return;
    }

    final Ref<Boolean> canNotGoBackRef = new Ref<Boolean>();
    final List<Fragment> fragments;
    try {
      fragments = myChunkFactory.goBack(CHUNK_SIZE, canNotGoBackRef);
    }
    catch (SVNException e) {
      throw new VcsException(e);
    }
    myCanNotGoBack = canNotGoBackRef.get().booleanValue();

    if (! fragments.isEmpty()) {
      // load
      ++ myCurrentIdx;
      myChunks.add(fragments);
    }
  }

  public void goForward() {
    -- myCurrentIdx;
  }

  public List<CommittedChangeList> getCurrent() {
    debugPrinting();
    return (List<CommittedChangeList>) ((myChunks.isEmpty()) ? Collections.<List<CommittedChangeList>>emptyList() :
                                        fragmentsToLists(myChunks.get(myCurrentIdx)));
  }

  private void debugPrinting() {
    LOG.debug("== showing screen (" + myCurrentIdx + "): ==");
    if (! myChunks.isEmpty()) {
      for (Fragment fragment : myChunks.get(myCurrentIdx)) {
        LOG.debug(fragment.getOrigin().toString() + " from: " + fragment.getList().get(0).getNumber() +
                 " to: " + fragment.getList().get(fragment.getList().size() - 1).getNumber());
      }
    }
    LOG.debug("== end of screen ==");
  }

  private List<CommittedChangeList> fragmentsToLists(final List<Fragment> fragments) {
    final List<CommittedChangeList> result = new ArrayList<CommittedChangeList>();
    for (Fragment fragment : fragments) {
      result.addAll(fragment.getList());
    }
    return result;
  }

  public void onBeforeClose() {
    if ((myVisuallyCached != null) && (myVisuallyCached.hadBeenSuccessfullyAccessed())) {
      myVisuallyCached.doCacheUpdate(myChunks);
      // keep also in internal cache
      InternallyCachedProvider.initCache(myChunks, myProject);
    } else if (myInternallyCached != null) {
      myInternallyCached.doCacheUpdate(myChunks);
    } else {
      InternallyCachedProvider.initCache(myChunks, myProject);
    }
  }

  private static class VisuallyCachedProvider extends CachedProvider {
    private final Project myProject;
    private final RepositoryLocation myLocation;

    private VisuallyCachedProvider(final Iterator<ChangesBunch> iterator, final Project project, final RepositoryLocation location) {
      super(iterator, Origin.VISUAL);
      myProject = project;
      myLocation = location;
    }

    public void doCacheUpdate(final List<List<Fragment>> fragmentsListList) {
      final List<CommittedChangeList> lists = getAllBeforeVisuallyCached(fragmentsListList);
      CommittedChangesCache.getInstance(myProject).submitExternallyLoaded(myLocation, myAlreadyReaded.getList().get(0).getNumber(), lists);
    }
  }

  private static class InternallyCachedProvider extends CachedProvider {
    private final Project myProject;
    private boolean myHolesDetected;

    private InternallyCachedProvider(final Iterator<ChangesBunch> iterator, final Project project) {
      super(iterator, Origin.INTERNAL);
      myProject = project;
    }

    @Override
    protected void addToLoaded(final ChangesBunch loaded) {
      myHolesDetected |= (! loaded.isConsistentWithPrevious());
      super.addToLoaded(loaded);
    }

    public static void initCache(final List<List<Fragment>> fragmentListList, final Project project) {
      final List<CommittedChangeList> lists = getAllBeforeVisuallyCached(fragmentListList);
      if (! lists.isEmpty()) {
        LoadedRevisionsCache.getInstance(project).put(lists, false, null);
      }
    }

    public void doCacheUpdate(final List<List<Fragment>> fragmentsListList) {
      final List<CommittedChangeList> lists = new ArrayList<CommittedChangeList>();
      LoadedRevisionsCache.Bunch bindAddress = null;
      boolean consistent = false;

      if (myHolesDetected) {
        boolean liveMet = false;
        for (int i = 0; i < fragmentsListList.size(); i++) {
          final List<Fragment> fragmentList = fragmentsListList.get(i);
          for (int j = 0; j < fragmentList.size(); j++) {
            final Fragment fragment = fragmentList.get(j);
            liveMet |= Origin.LIVE.equals(fragment.getOrigin());
            if (Origin.INTERNAL.equals(fragment.getOrigin())) {
              bindAddress = ((LoadedRevisionsCache.Bunch) fragment.getOriginBunch()).getNext();
              // latest element
              if ((i == (fragmentsListList.size() - 1)) && (j == (fragmentList.size() - 1))) {
                lists.addAll(fragment.getOriginBunch().getList());
                consistent = fragment.getOriginBunch().isConsistentWithPrevious();
                break;
              }
            }
            lists.addAll(fragment.getList());
          }
        }
        if (! liveMet) {
          return;
        }
      } else {
        // until _first_internally
        for (List<Fragment> fragmentList : fragmentsListList) {
          for (Fragment fragment : fragmentList) {
            if (Origin.INTERNAL.equals(fragment.getOrigin())) {
              bindAddress = (LoadedRevisionsCache.Bunch) fragment.getOriginBunch();
              consistent = true;
              break;
            }
            lists.addAll(fragment.getList());
          }
        }
      }

      if (! lists.isEmpty()) {
        LoadedRevisionsCache.getInstance(myProject).put(lists, consistent, bindAddress);
      }
    }
  }
}
