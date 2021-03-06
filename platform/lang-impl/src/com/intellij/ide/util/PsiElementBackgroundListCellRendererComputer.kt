// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.util

import com.intellij.navigation.TargetPresentation
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.impl.coroutineDispatchingContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.runUnderIndicator
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.psi.PsiElement
import com.intellij.ui.AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED
import com.intellij.ui.ScreenUtil
import com.intellij.ui.popup.AbstractPopup
import com.intellij.util.ObjectUtils
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.UIUtil.runWhenChanged
import com.intellij.util.ui.UIUtil.runWhenHidden
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.asCompletableFuture
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.Future
import javax.swing.AbstractListModel
import javax.swing.JList
import javax.swing.event.ListDataEvent

internal fun getComputer(list: JList<*>, renderer: PsiElementListCellRenderer<*>): PsiElementBackgroundListCellRendererComputer {
  return computers(list).computeIfAbsent(renderer) {
    PsiElementBackgroundListCellRendererComputer(list, renderer)
  }
}

private typealias Computers = HashMap<PsiElementListCellRenderer<*>, PsiElementBackgroundListCellRendererComputer>

private val computersKey = ObjectUtils.sentinel("renderer computers")

private fun computers(list: JList<*>): Computers {
  val existing = list.getClientProperty(computersKey)
  if (existing != null) {
    @Suppress("UNCHECKED_CAST")
    return existing as Computers
  }
  val computers = Computers()
  list.putClientProperty(computersKey, computers)
  list.putClientProperty(ANIMATION_IN_RENDERER_ALLOWED, true)
  fun cleanUp() {
    list.putClientProperty(computersKey, null)
    list.putClientProperty(ANIMATION_IN_RENDERER_ALLOWED, null)
    for (computer in computers.values) {
      computer.dispose()
    }
  }
  runWhenHidden(list, ::cleanUp)
  runWhenChanged(list, "cellRenderer", ::cleanUp)
  return computers
}

internal class PsiElementBackgroundListCellRendererComputer(
  private val list: JList<*>,
  private val renderer: PsiElementListCellRenderer<*>,
) {

  private val myCoroutineScope = CoroutineScope(Job())
  private val myRepaintQueue = Channel<Unit>(Channel.CONFLATED)
  private val uiDispatcher = AppUIExecutor.onUiThread().later().coroutineDispatchingContext()
  private val myComponentsMap: MutableMap<PsiElement, Future<TargetPresentation>> = HashMap()

  init {
    myCoroutineScope.launch(uiDispatcher) {
      // A tick happens when an element has finished computing.
      // Several elements are also merged into a single tick because the Channel is CONFLATED.
      for (tick in myRepaintQueue) {
        notifyModelChanged(list)
        redrawListAndContainer(list)
        delay(100) // update UI no more often that once in 100ms
      }
    }
  }

  fun dispose() {
    myCoroutineScope.cancel()
    myRepaintQueue.close()
    myComponentsMap.clear()
  }

  @RequiresEdt
  fun computePresentationAsync(element: PsiElement): Future<TargetPresentation> {
    return myComponentsMap.computeIfAbsent(element, ::doComputePresentationAsync)
  }

  private fun doComputePresentationAsync(element: PsiElement): Future<TargetPresentation> {
    val result = myCoroutineScope.async {
      //delay((Math.random() * 3000 + 2000).toLong()) // uncomment to add artificial delay to check out how it looks in UI
      readAction { progress ->
        runUnderIndicator(progress) {
          renderer.computePresentation(element)
        }
      }
    }
    myCoroutineScope.launch {
      result.join()
      myRepaintQueue.send(Unit) // repaint _after_ the resulting future is done
    }
    return result.asCompletableFuture()
  }
}

/**
 * This method forces [javax.swing.plaf.basic.BasicListUI.updateLayoutStateNeeded] to become non-zero via the path:
 * [JList.getModel]
 * -> [AbstractListModel.getListDataListeners]
 * -> [javax.swing.event.ListDataListener.contentsChanged]
 * -> [javax.swing.plaf.basic.BasicListUI.Handler.contentsChanged]
 *
 * It's needed so next call of [javax.swing.plaf.basic.BasicListUI.maybeUpdateLayoutState] will recompute list's preferred size.
 */
private fun notifyModelChanged(list: JList<*>) {
  val model = list.model
  if (model !is AbstractListModel) {
    error("Unsupported list model: " + model.javaClass.name)
  }
  val size = model.size
  if (size == 0) {
    return
  }
  val listeners = model.listDataListeners
  if (listeners.isEmpty()) {
    return
  }
  val e = ListDataEvent(list, ListDataEvent.CONTENTS_CHANGED, 0, size - 1)
  for (listener in listeners) {
    listener.contentsChanged(e)
  }
}

private fun redrawListAndContainer(list: JList<*>) {
  if (!list.isShowing) {
    return
  }
  resizePopup(list)
  list.repaint()
}

private fun resizePopup(list: JList<*>) {
  val popup = PopupUtil.getPopupContainerFor(list) ?: return
  if (popup is AbstractPopup && popup.dimensionServiceKey != null) {
    return
  }
  val popupLocation = popup.locationOnScreen
  val rectangle = Rectangle(popupLocation, list.parent.preferredSize)
  ScreenUtil.fitToScreen(rectangle)
  if (rectangle.width > popup.size.width) { // don't shrink popup
    popup.setLocation(Point(rectangle.x, popupLocation.y)) // // don't change popup vertical position on screen
    popup.size = Dimension(rectangle.width, popup.size.height) // don't change popup height
  }
}
