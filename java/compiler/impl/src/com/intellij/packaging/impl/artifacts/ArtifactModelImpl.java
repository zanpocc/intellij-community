// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.packaging.impl.artifacts;

import com.intellij.openapi.roots.ProjectModelExternalSource;
import com.intellij.packaging.artifacts.*;
import com.intellij.packaging.elements.CompositePackagingElement;
import com.intellij.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ArtifactModelImpl extends ArtifactModelBase implements ModifiableArtifactModel {
  private final List<ArtifactImpl> myOriginalArtifacts;
  private final ArtifactManagerImpl myArtifactManager;
  private final Map<ArtifactImpl, ArtifactImpl> myArtifact2ModifiableCopy = new HashMap<>();
  private final Map<ArtifactImpl, ArtifactImpl> myModifiable2Original = new HashMap<>();
  private final EventDispatcher<ArtifactListener> myDispatcher = EventDispatcher.create(ArtifactListener.class);

  public ArtifactModelImpl(ArtifactManagerImpl artifactManager, List<? extends ArtifactImpl> originalArtifacts) {
    myArtifactManager = artifactManager;
    myOriginalArtifacts = new ArrayList<>(originalArtifacts);
    addListener(new ArtifactListener() {
      @Override
      public void artifactChanged(@NotNull Artifact artifact, @NotNull String oldName) {
        artifactsChanged();
      }
    });
  }

  @Override
  protected List<? extends Artifact> getArtifactsList() {
    final List<ArtifactImpl> list = new ArrayList<>();
    for (ArtifactImpl artifact : myOriginalArtifacts) {
      final ArtifactImpl copy = myArtifact2ModifiableCopy.get(artifact);
      if (copy != null) {
        list.add(copy);
      }
      else {
        list.add(artifact);
      }
    }
    return list;
  }

  @Override
  @NotNull
  public ModifiableArtifact addArtifact(@NotNull final String name, @NotNull ArtifactType artifactType) {
    return addArtifact(name, artifactType, artifactType.createRootElement(name));
  }

  @Override
  @NotNull
  public ModifiableArtifact addArtifact(@NotNull String name, @NotNull ArtifactType artifactType, CompositePackagingElement<?> rootElement) {
    return addArtifact(name, artifactType, rootElement, null);
  }

  @Override
  public @NotNull ModifiableArtifact addArtifact(@NotNull String name,
                                                 @NotNull ArtifactType artifactType,
                                                 CompositePackagingElement<?> rootElement,
                                                 @Nullable ProjectModelExternalSource externalSource) {
    String uniqueName = generateUniqueName(name);
    String outputPath = ArtifactUtil.getDefaultArtifactOutputPath(uniqueName, myArtifactManager.getProject());
    ArtifactImpl artifact = new ArtifactImpl(uniqueName, artifactType, false, rootElement, outputPath, externalSource, myDispatcher, false);
    addArtifact(artifact);
    return artifact;
  }

  void addArtifact(ArtifactImpl artifact) {
    myOriginalArtifacts.add(artifact);
    myArtifact2ModifiableCopy.put(artifact, artifact);
    myModifiable2Original.put(artifact, artifact);

    artifactsChanged();
    myDispatcher.getMulticaster().artifactAdded(artifact);
  }

  private String generateUniqueName(String baseName) {
    String name = baseName;
    int i = 2;
    while (true) {
      if (findArtifact(name) == null) {
        return name;
      }
      name = baseName + i++;
    }
  }

  @Override
  public void addListener(@NotNull ArtifactListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeListener(@NotNull ArtifactListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeArtifact(@NotNull Artifact artifact) {
    final ArtifactImpl artifactImpl = (ArtifactImpl)artifact;
    ArtifactImpl original = myModifiable2Original.remove(artifactImpl);
    if (original != null) {
      myOriginalArtifacts.remove(original);
    }
    else {
      original = artifactImpl;
    }
    myArtifact2ModifiableCopy.remove(original);
    myOriginalArtifacts.remove(original);
    artifactsChanged();
    myDispatcher.getMulticaster().artifactRemoved(original);
  }

  @Override
  @NotNull
  public ModifiableArtifact getOrCreateModifiableArtifact(@NotNull Artifact artifact) {
    final ArtifactImpl artifactImpl = (ArtifactImpl)artifact;
    if (myModifiable2Original.containsKey(artifactImpl)) {
      return artifactImpl;
    }

    ArtifactImpl modifiableCopy = myArtifact2ModifiableCopy.get(artifactImpl);
    if (modifiableCopy == null) {
      modifiableCopy = artifactImpl.createCopy(myDispatcher);
      myDispatcher.getMulticaster().artifactChanged(modifiableCopy, artifact.getName());
      myArtifact2ModifiableCopy.put(artifactImpl, modifiableCopy);
      myModifiable2Original.put(modifiableCopy, artifactImpl);
      artifactsChanged();
    }
    return modifiableCopy;
  }

  @Override
  public @NotNull Artifact getOriginalArtifact(@NotNull Artifact artifact) {
    ArtifactImpl original = myModifiable2Original.get(artifact);
    return original != null ? original : artifact;
  }

  @Override
  @NotNull
  public ArtifactImpl getArtifactByOriginal(@NotNull Artifact artifact) {
    final ArtifactImpl artifactImpl = (ArtifactImpl)artifact;
    final ArtifactImpl copy = myArtifact2ModifiableCopy.get(artifactImpl);
    return copy != null ? copy : artifactImpl;
  }

  @Override
  public boolean isModified() {
    return !myOriginalArtifacts.equals(myArtifactManager.getArtifactsList()) || !myArtifact2ModifiableCopy.isEmpty();
  }

  @Override
  public void commit() {
    myArtifactManager.commit(this);
  }

  @Override
  public void dispose() {
    List<Artifact> artifacts = new ArrayList<>();
    for (ArtifactImpl artifact : myModifiable2Original.keySet()) {
      if (myModifiable2Original.get(artifact).equals(artifact)) {
        artifacts.add(artifact);
      }
    }
    ((ArtifactPointerManagerImpl)ArtifactPointerManager.getInstance(myArtifactManager.getProject())).disposePointers(artifacts);
  }

  @Override
  @Nullable
  public ArtifactImpl getModifiableCopy(@NotNull Artifact artifact) {
    //noinspection SuspiciousMethodCalls
    return myArtifact2ModifiableCopy.get(artifact);
  }

  public List<ArtifactImpl> getOriginalArtifacts() {
    return myOriginalArtifacts;
  }
}
