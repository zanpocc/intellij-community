// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.siyeh.ig.resources;

import com.intellij.codeInspection.ui.InspectionOptionsPanel;
import com.intellij.codeInspection.ui.ListTable;
import com.intellij.codeInspection.ui.ListWrappingTableModel;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.psi.*;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.psiutils.TypeUtils;
import com.siyeh.ig.ui.UiUtils;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class IOResourceInspection extends ResourceInspection {
  protected static final String[] IO_TYPES =
    {
      "java.io.InputStream", "java.io.OutputStream", "java.io.Reader", "java.io.Writer",
      "java.io.RandomAccessFile", "java.util.zip.ZipFile", "java.io.Closeable"
    };
  final List<String> ignoredTypes = new ArrayList<>();
  @SuppressWarnings({"PublicField"})
  public String ignoredTypesString = "java.io.ByteArrayOutputStream" +
                                     ',' + "java.io.ByteArrayInputStream" +
                                     ',' + "java.io.StringBufferInputStream" +
                                     ',' + "java.io.CharArrayWriter" +
                                     ',' + "java.io.CharArrayReader" +
                                     ',' + "java.io.StringWriter" +
                                     ',' + "java.io.StringReader";

  public IOResourceInspection() {
    parseString(ignoredTypesString, ignoredTypes);
  }

  @Override
  public @NotNull JComponent createOptionsPanel() {
    final InspectionOptionsPanel panel = new InspectionOptionsPanel();
    final ListTable table =
      new ListTable(new ListWrappingTableModel(ignoredTypes, InspectionGadgetsBundle.message("ignored.io.resource.types")));
    final JPanel tablePanel =
      UiUtils.createAddRemoveTreeClassChooserPanel(
        InspectionGadgetsBundle.message("choose.io.resource.type.to.ignore"),
        InspectionGadgetsBundle.message("ignored.io.resource.types.label"),
        table,
        true,
        IO_TYPES);
    panel.addGrowing(tablePanel);
    panel.add(super.createOptionsPanel());
    return panel;
  }

  @Override
  @NotNull
  public String getID() {
    return "IOResourceOpenedButNotSafelyClosed";
  }

  @Override
  public void readSettings(@NotNull Element element) throws InvalidDataException {
    super.readSettings(element);
    parseString(ignoredTypesString, ignoredTypes);
  }

  @Override
  public void writeSettings(@NotNull Element element) throws WriteExternalException {
    ignoredTypesString = formatString(ignoredTypes);
    super.writeSettings(element);
  }

  @Override
  public boolean isResourceCreation(PsiExpression expression) {
    if (expression instanceof PsiNewExpression) {
      return TypeUtils.expressionHasTypeOrSubtype(expression, IO_TYPES) != null && !isIgnoredType(expression);
    }
    else if (expression instanceof PsiMethodCallExpression) {
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expression;
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      final String methodName = methodExpression.getReferenceName();
      if (!"getResourceAsStream".equals(methodName)) {
        return false;
      }
      final PsiExpression qualifier = methodExpression.getQualifierExpression();
      if (qualifier == null ||
          TypeUtils.expressionHasTypeOrSubtype(qualifier, CommonClassNames.JAVA_LANG_CLASS, "java.lang.ClassLoader") == null) {
        return false;
      }
      return TypeUtils.expressionHasTypeOrSubtype(expression, "java.io.InputStream");
    }
    return false;
  }

  private boolean isIgnoredType(PsiExpression expression) {
    return TypeUtils.expressionHasTypeOrSubtype(expression, ignoredTypes);
  }
}
