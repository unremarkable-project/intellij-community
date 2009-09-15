package com.intellij.openapi.command.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.NoneGroupId;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.command.undo.DocumentReference;
import com.intellij.openapi.command.undo.UndoableAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * author: lesya
 */

public class CommandMerger implements Disposable {
  private final UndoManagerImpl myManager;
  private Object myLastGroupId = null;
  private boolean myIsComplex = false;
  private boolean myOnlyUndoTransparents = true;
  private boolean myHasUndoTransparents = false;
  private String myCommandName = null;
  private ArrayList<UndoableAction> myCurrentActions = new ArrayList<UndoableAction>();
  private Set<DocumentReference> myAffectedDocuments = new HashSet<DocumentReference>();
  private EditorAndState myStateBefore;
  private EditorAndState myStateAfter;
  private UndoConfirmationPolicy myUndoConfirmationPolicy = UndoConfirmationPolicy.DEFAULT;

  CommandMerger(@NotNull UndoManagerImpl manager, @NotNull EditorFactory editorFactory) {
    myManager = manager;
    EditorEventMulticaster eventMulticaster = editorFactory.getEventMulticaster();
    DocumentAdapter documentListener = new DocumentAdapter() {
      public void documentChanged(DocumentEvent e) {
        Document document = e.getDocument();
        if (myManager.isActive() && !myManager.isUndoInProgress() && !myManager.isRedoInProgress()) {
          myManager.getRedoStacksHolder().getStack(document).clear();
        }
      }
    };
    eventMulticaster.addDocumentListener(documentListener, this);
  }

  public void dispose() {
    clearDocumentRefs();
  }

  public void clearDocumentRefs() {
    myLastGroupId = null;
  }

  public void add(UndoableAction action, boolean isUndoTransparent) {
    if (!isUndoTransparent) myOnlyUndoTransparents = false;
    if (isUndoTransparent) myHasUndoTransparents = true;
    myCurrentActions.add(action);
    DocumentReference[] refs = action.getAffectedDocuments();
    if (refs != null) {
      Collections.addAll(myAffectedDocuments, refs);
    }
    myIsComplex |= action.shouldConfirmUndo() || !isUndoTransparent && affectsMultiplePhysicalDocs();
  }

  private boolean affectsMultiplePhysicalDocs() {
    return areMultiplePhysicalDocsAffected(myAffectedDocuments);
  }

  protected static boolean areMultiplePhysicalDocsAffected(Collection<DocumentReference> rr) {
    if (rr.size() < 2) return false;
    int count = 0;
    for (DocumentReference docRef : rr) {
      VirtualFile file = docRef.getFile();
      if (file instanceof LightVirtualFile) continue;
      count++;
    }

    return count > 1;
  }

  public void commandFinished(String commandName, Object groupId, CommandMerger nextCommandToMerge) {
    if (!shouldMerge(groupId, nextCommandToMerge)) {
      flushCurrentCommand();
      myManager.compact();
    }
    merge(nextCommandToMerge);
    clearRedoStacks(nextCommandToMerge);

    myLastGroupId = groupId;
    if (myCommandName == null) myCommandName = commandName;
  }

  private void clearRedoStacks(CommandMerger m) {
    for (DocumentReference d : m.myAffectedDocuments) {
      myManager.getRedoStacksHolder().clearFileStack(d);
    }

    if (isComplex()){
      myManager.getRedoStacksHolder().clearGlobalStack();
    }
  }

  private boolean shouldMerge(Object groupId, CommandMerger nextCommandToMerge) {
    if (myOnlyUndoTransparents && myHasUndoTransparents ||
        nextCommandToMerge.myOnlyUndoTransparents && nextCommandToMerge.myHasUndoTransparents) {
      return myAffectedDocuments.equals(nextCommandToMerge.myAffectedDocuments);
    }
    return !myIsComplex && !nextCommandToMerge.isComplex() && canMergeGroup(groupId, myLastGroupId);
  }

  public static boolean canMergeGroup(Object groupId, Object lastGroupId) {
    return groupId != null && !(groupId instanceof NoneGroupId) && Comparing.equal(lastGroupId, groupId);
  }

  boolean isComplex() {
    return myIsComplex;
  }

  private void merge(CommandMerger nextCommandToMerge) {
    setBeforeState(nextCommandToMerge.myStateBefore);
    myCurrentActions.addAll(nextCommandToMerge.myCurrentActions);
    myAffectedDocuments.addAll(nextCommandToMerge.myAffectedDocuments);
    myIsComplex |= nextCommandToMerge.myIsComplex;
    myOnlyUndoTransparents &= nextCommandToMerge.myOnlyUndoTransparents;
    myHasUndoTransparents |= nextCommandToMerge.myHasUndoTransparents;
    myStateAfter = nextCommandToMerge.myStateAfter;
    mergeUndoConfirmationPolicy(nextCommandToMerge.getUndoConfirmationPolicy());
  }

  public UndoConfirmationPolicy getUndoConfirmationPolicy() {
    return myUndoConfirmationPolicy;
  }

  public void flushCurrentCommand() {
    if (!isEmpty()) {
      int commandTimestamp = myManager.nextCommandTimestamp();
      UndoableGroup undoableGroup = new UndoableGroup(myCommandName, myIsComplex, myManager.getProject(), myStateBefore, myStateAfter,
                                                      commandTimestamp, myUndoConfirmationPolicy,
                                                      myHasUndoTransparents && myOnlyUndoTransparents);
      undoableGroup.addTailActions(myCurrentActions);
      addToAllStacks(undoableGroup);
    }

    reset();
  }

  private void addToAllStacks(UndoableGroup group) {
    for (DocumentReference each : myAffectedDocuments) {
      myManager.getUndoStacksHolder().addToLocalStack(each, group);
    }

    if (myIsComplex) {
      if (!group.getAffectedDocuments().isEmpty() || group.isComplex()) {
        myManager.getUndoStacksHolder().addToGlobalStack(group);
      }
    }
  }

  public void undoOrRedo(FileEditor editor, boolean isUndo) {
    flushCurrentCommand();
    UndoOrRedo.execute(myManager, editor, isUndo);
  }

  public boolean isEmpty() {
    return myCurrentActions.isEmpty();
  }

  public boolean hasChangesOf(DocumentReference ref) {
    for (UndoableAction action : myCurrentActions) {
      DocumentReference[] refs = action.getAffectedDocuments();
      if (refs == null || Arrays.asList(refs).contains(ref)) return true;
    }
    return false;
  }

  public void reset() {
    myCurrentActions = new ArrayList<UndoableAction>();
    myAffectedDocuments = new HashSet<DocumentReference>();
    myLastGroupId = null;
    myIsComplex = false;
    myOnlyUndoTransparents = true;
    myHasUndoTransparents = false;
    myCommandName = null;
    myStateAfter = null;
    myStateBefore = null;
    myUndoConfirmationPolicy = UndoConfirmationPolicy.DEFAULT;
  }

  public void setBeforeState(EditorAndState state) {
    if (myStateBefore == null || isEmpty()) {
      myStateBefore = state;
    }
  }

  public void setAfterState(EditorAndState state) {
    myStateAfter = state;
  }

  public Collection<DocumentReference> getAffectedDocuments() {
    return myAffectedDocuments;
  }

  public void mergeUndoConfirmationPolicy(UndoConfirmationPolicy undoConfirmationPolicy) {
    if (myUndoConfirmationPolicy == UndoConfirmationPolicy.DEFAULT) {
      myUndoConfirmationPolicy = undoConfirmationPolicy;
    }
    else if (myUndoConfirmationPolicy == UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION) {
      if (undoConfirmationPolicy == UndoConfirmationPolicy.REQUEST_CONFIRMATION) {
        myUndoConfirmationPolicy = UndoConfirmationPolicy.REQUEST_CONFIRMATION;
      }
    }
  }

  void markAsComplex() {
    myIsComplex = true;
  }
}
