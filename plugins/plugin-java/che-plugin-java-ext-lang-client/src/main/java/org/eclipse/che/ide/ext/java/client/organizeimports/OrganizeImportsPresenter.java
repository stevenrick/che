/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.ide.ext.java.client.organizeimports;

import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.document.Document;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.filewatcher.ClientServerEventService;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.JavaLocalizationConstant;
import org.eclipse.che.ide.ext.java.client.resource.SourceFolderMarker;
import org.eclipse.che.ide.ext.java.client.service.JavaLanguageExtensionServiceClient;
import org.eclipse.che.ide.ext.java.shared.dto.Change;
import org.eclipse.che.ide.util.Pair;
import org.eclipse.che.jdt.ls.extension.api.dto.ImportConflicts;
import org.eclipse.che.jdt.ls.extension.api.dto.OrganizeImports;
import org.eclipse.che.jdt.ls.extension.api.dto.OrganizeImportsResult;
import org.eclipse.che.plugin.languageserver.ide.editor.quickassist.ApplyWorkspaceEditAction;

/**
 * The class that manages conflicts with organize imports if if they occur.
 *
 * @author Valeriy Svydenko
 */
@Singleton
public class OrganizeImportsPresenter implements OrganizeImportsView.ActionDelegate {
  private final OrganizeImportsView view;
  private final JavaLanguageExtensionServiceClient client;
  private final DtoFactory dtoFactory;
  private final JavaLocalizationConstant locale;
  private final NotificationManager notificationManager;
  private final ClientServerEventService clientServerEventService;
  private final ApplyWorkspaceEditAction applyWorkspaceEditAction;

  private int page;
  private List<ImportConflicts> choices;
  private Map<Integer, String> selected;
  private VirtualFile file;
  private Document document;
  private EditorPartPresenter editor;

  @Inject
  public OrganizeImportsPresenter(
      OrganizeImportsView view,
      JavaLanguageExtensionServiceClient client,
      DtoFactory dtoFactory,
      JavaLocalizationConstant locale,
      NotificationManager notificationManager,
      ClientServerEventService clientServerEventService,
      ApplyWorkspaceEditAction applyWorkspaceEditAction) {
    this.view = view;
    this.client = client;
    this.clientServerEventService = clientServerEventService;
    this.applyWorkspaceEditAction = applyWorkspaceEditAction;
    this.view.setDelegate(this);

    this.dtoFactory = dtoFactory;
    this.locale = locale;
    this.notificationManager = notificationManager;
  }

  /**
   * Make Organize imports operation. If the operation doesn't have conflicts all imports will be
   * applied otherwise a special window will be showed for resolving conflicts.
   *
   * @param editor current active editor
   */
  public void organizeImports(EditorPartPresenter editor) {
    this.editor = editor;
    this.document = ((TextEditor) editor).getDocument();
    this.file = editor.getEditorInput().getFile();

    if (file instanceof Resource) {
      final Optional<Resource> srcFolder =
          ((Resource) file).getParentWithMarker(SourceFolderMarker.ID);

      if (!srcFolder.isPresent()) {
        return;
      }

      clientServerEventService
          .sendFileTrackingSuspendEvent()
          .then(
              arg -> {
                doOrganizeImports(file.getLocation().toString());
              });
    }
  }

  private Promise<OrganizeImportsResult> doOrganizeImports(String path) {
    OrganizeImports organizeImports = dtoFactory.createDto(OrganizeImports.class);
    organizeImports.setChoices(Collections.emptyList());
    organizeImports.setResourceUri(path);

    return client
        .organizeImports(organizeImports)
        .then(
            result -> {
              if (result.getImportConflicts() != null && !result.getImportConflicts().isEmpty()) {
                show(result.getImportConflicts());
              } else {
                applyWorkspaceEditAction.applyWorkspaceEdit(result.getWorkspaceEdit());
              }

              clientServerEventService.sendFileTrackingResumeEvent();
            })
        .catchError(
            error -> {
              String title = locale.failedToProcessOrganizeImports();
              String message = error.getMessage();
              notificationManager.notify(title, message, FAIL, FLOAT_MODE);

              clientServerEventService.sendFileTrackingResumeEvent();
            });
  }

  /** {@inheritDoc} */
  @Override
  public void onNextButtonClicked() {
    selected.put(page++, view.getSelectedImport());
    if (!selected.containsKey(page)) {
      selected.put(page, view.getSelectedImport());
    }
    view.setSelectedImport(selected.get(page));
    view.changePage(choices.get(page));
    updateButtonsState();
  }

  /** {@inheritDoc} */
  @Override
  public void onBackButtonClicked() {
    selected.put(page--, view.getSelectedImport());
    view.setSelectedImport(selected.get(page));
    view.changePage(choices.get(page));
    updateButtonsState();
  }

  /** {@inheritDoc} */
  @Override
  public void onFinishButtonClicked() {
    selected.put(page, view.getSelectedImport());

    OrganizeImports organizeImports = dtoFactory.createDto(OrganizeImports.class);
    organizeImports.setResourceUri(file.getLocation().toString());
    organizeImports.setChoices(new ArrayList<>(selected.values()));

    clientServerEventService
        .sendFileTrackingSuspendEvent()
        .then(
            successful -> {
              client
                  .organizeImports(organizeImports)
                  .then(
                      result -> {
                        applyWorkspaceEditAction.applyWorkspaceEdit(result.getWorkspaceEdit());
                        clientServerEventService.sendFileTrackingResumeEvent();
                        view.hide();
                      })
                  .catchError(
                      error -> {
                        String title = locale.failedToProcessOrganizeImports();
                        String message = error.getMessage();
                        notificationManager.notify(title, message, FAIL, FLOAT_MODE);

                        clientServerEventService.sendFileTrackingResumeEvent();
                        view.hide();
                      });
            });
  }

  /** {@inheritDoc} */
  @Override
  public void onCancelButtonClicked() {
    ((TextEditor) editor).setFocus();
  }

  /** Show Organize Imports panel with the special information. */
  private void show(List<ImportConflicts> choices) {
    if (choices == null || choices.isEmpty()) {
      return;
    }

    this.choices = choices;

    page = 0;
    selected = new HashMap<>(choices.size());

    ImportConflicts conflict = choices.get(0);

    String selection = conflict.getMatches().get(0);
    selected.put(page, choices.get(0).getMatches().get(0));
    view.setSelectedImport(selection);

    updateButtonsState();

    view.show(choices.get(page));
  }

  private void updateButtonsState() {
    view.setEnableBackButton(!isFirstPage());
    view.setEnableNextButton(!isLastPage());
    view.setEnableFinishButton(selected.size() == choices.size());
  }

  private boolean isFirstPage() {
    return page == 0;
  }

  private boolean isLastPage() {
    return (choices.size() - 1) == page;
  }
}
