/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.dgs.plugin.services.internal;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.psi.util.PsiModificationTracker;
import com.netflix.dgs.plugin.services.DgsComponentIndex;
import com.netflix.dgs.plugin.services.DgsService;
import com.netflix.dgs.plugin.services.UDgsDataProcessor;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UastContextKt;

import java.util.Set;

public class DgsServiceImpl implements DgsService, Disposable {
    private final Project project;
    private final Set<String> annotations = Set.of(
            "DgsQuery",
            "DgsMutation",
            "DgsSubscription",
            "DgsData",
            "DgsEntityFetcher");
    private volatile DgsComponentIndex cachedComponentIndex;

    public DgsServiceImpl(Project project) {
        this.project = project;
    }

    private volatile long javaModificationCount;
    private volatile long kotlinModificationCount;

    @Override
    public DgsComponentIndex getDgsComponentIndex() {
        ModificationTracker javaModificationTracker = PsiModificationTracker.SERVICE.getInstance(project).forLanguage(JavaLanguage.INSTANCE);
        ModificationTracker kotlinModificationTracker = PsiModificationTracker.SERVICE.getInstance(project).forLanguage(KotlinLanguage.INSTANCE);

        if (cachedComponentIndex != null && javaModificationCount == javaModificationTracker.getModificationCount() && kotlinModificationCount == kotlinModificationTracker.getModificationCount()) {
            return cachedComponentIndex;
        } else {
            javaModificationCount = javaModificationTracker.getModificationCount();
            kotlinModificationCount = kotlinModificationTracker.getModificationCount();

            StubIndex stubIndex = StubIndex.getInstance();

            long startTime = System.currentTimeMillis();
            DgsComponentIndex dgsComponentIndex = new DgsComponentIndex();
            GraphQLSchemaRegistry graphQLSchemaRegistry = project.getService(GraphQLSchemaRegistry.class);
            var processor = new UDgsDataProcessor(graphQLSchemaRegistry, dgsComponentIndex);

            annotations.forEach(dataFetcherAnnotation -> {
                stubIndex.processElements(JavaStubIndexKeys.ANNOTATIONS, dataFetcherAnnotation, project, GlobalSearchScope.projectScope(project), PsiAnnotation.class, annotation -> {
                    UAnnotation uElement = (UAnnotation) UastContextKt.toUElement(annotation);
                    if(uElement != null) {
                        processor.process(uElement);
                    }
                    return true;
                });
            });

            StubIndexKey<String, KtAnnotationEntry> key = KotlinAnnotationsIndex.getInstance().getKey();
            stubIndex.processAllKeys(key, project, annotation -> {
                if (annotations.contains(annotation)) {
                    System.out.println(annotation);
                    stubIndex.getElements(key, annotation, project, GlobalSearchScope.projectScope(project), KtAnnotationEntry.class).forEach(dataFetcherAnnotation -> {
                        UAnnotation uElement = (UAnnotation) UastContextKt.toUElement(dataFetcherAnnotation);
                        if(uElement != null) {
                            processor.process(uElement);
                        }
                    });
                }
                return true;
            });

            cachedComponentIndex = dgsComponentIndex;

            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("DGS indexing took " + totalTime + " ms");

            return dgsComponentIndex;
        }
    }

    @Override
    public void clearCache() {
        cachedComponentIndex = null;
    }

    @Override
    public void dispose() {

    }
}
