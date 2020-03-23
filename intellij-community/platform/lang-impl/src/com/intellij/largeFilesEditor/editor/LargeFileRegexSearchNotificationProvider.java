// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.largeFilesEditor.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorBundle;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LargeFileRegexSearchNotificationProvider extends EditorNotifications.Provider<EditorNotificationPanel> {
  private static final Key<EditorNotificationPanel> KEY = Key.create("large.file.editor.regex.search.notification");
  private static final Key<String> HIDDEN_KEY = Key.create("large.file.editor.regex.search.notification.hidden");

  @NotNull
  @Override
  public Key<EditorNotificationPanel> getKey() {
    return KEY;
  }

  @Nullable
  @Override
  public EditorNotificationPanel createNotificationPanel(@NotNull VirtualFile file,
                                                         @NotNull FileEditor fileEditor,
                                                         @NotNull Project project) {
    if (!(fileEditor instanceof LargeFileEditor)) return null;

    LargeFileEditor largeFileEditor = (LargeFileEditor)fileEditor;
    Editor editor = largeFileEditor.getEditor();

    if (editor.getUserData(HIDDEN_KEY) != null) return null;

    if (!largeFileEditor.getSearchManager().canShowRegexSearchWarning()) return null;

    EditorNotificationPanel panel = new EditorNotificationPanel();
    panel.createActionLabel(EditorBundle.message("large.file.editor.hide.notification.action.text"), () -> {
      editor.putUserData(HIDDEN_KEY, "true");
      update(file, project);
    });

    // "200 Kb", because it's the length of 2 pages (regex search realization specificities)
    return panel.text(EditorBundle.message("message.warning.about.regex.search.limitations", String.valueOf(200)));
  }

  private static void update(@NotNull VirtualFile file, @NotNull Project project) {
    EditorNotifications.getInstance(project).updateNotifications(file);
  }
}
