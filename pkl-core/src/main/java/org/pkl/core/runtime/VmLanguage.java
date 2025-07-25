/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.ContextThreadLocal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextPolicy;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import org.pkl.core.ast.builder.AstBuilder;
import org.pkl.core.module.ModuleKey;
import org.pkl.core.module.ResolvedModuleKey;
import org.pkl.core.util.IoUtils;
import org.pkl.core.util.Nullable;
import org.pkl.parser.Parser;
import org.pkl.parser.ParserError;
import org.pkl.parser.syntax.Module;

@TruffleLanguage.Registration(
    id = "pkl",
    name = "Pkl",
    version = "0.30.0-dev",
    characterMimeTypes = VmLanguage.MIME_TYPE,
    contextPolicy = ContextPolicy.SHARED)
public final class VmLanguage extends TruffleLanguage<VmContext> {
  public static final String MIME_TYPE = "application/x-pkl";

  private static final LanguageReference<VmLanguage> REFERENCE =
      LanguageReference.create(VmLanguage.class);

  public static VmLanguage get(@Nullable Node node) {
    return REFERENCE.get(node);
  }

  public final ContextThreadLocal<VmLocalContext> localContext =
      locals.createContextThreadLocal((ignoredCtx, ignoredThread) -> new VmLocalContext());

  @Override
  protected VmContext createContext(Env env) {
    return new VmContext();
  }

  @Override
  public CallTarget parse(ParsingRequest request) {
    throw new UnsupportedOperationException("parse");
  }

  public VmTyped loadModule(ModuleKey moduleKey) {
    var context = VmContext.get(null);

    return context
        .getModuleCache()
        .getOrLoad(
            moduleKey,
            context.getSecurityManager(),
            context.getModuleResolver(),
            VmUtils::createEmptyModule,
            this::initializeModule,
            null);
  }

  public VmTyped loadModule(ModuleKey moduleKey, @Nullable Node importNode) {
    var context = VmContext.get(null);

    return context
        .getModuleCache()
        .getOrLoad(
            moduleKey,
            context.getSecurityManager(),
            context.getModuleResolver(),
            VmUtils::createEmptyModule,
            this::initializeModule,
            importNode);
  }

  void initializeModule(
      ModuleKey moduleKey,
      ResolvedModuleKey resolvedModuleKey,
      ModuleResolver moduleResolver,
      Source source,
      VmTyped emptyModule,
      @Nullable Node importNode) {
    var parser = new Parser();
    Module moduleContext;
    var sourceStr = source.getCharacters().toString();
    try {
      moduleContext = parser.parseModule(sourceStr);
    } catch (ParserError e) {
      var moduleName = IoUtils.inferModuleName(moduleKey);
      MinPklVersionChecker.check(moduleName, e.getPartialParseResult(), importNode, sourceStr);
      throw VmUtils.toVmException(e, source, moduleName);
    }

    var builder =
        AstBuilder.create(
            source, this, moduleContext, moduleKey, resolvedModuleKey, moduleResolver);
    var moduleNode = builder.visitModule(moduleContext);
    moduleNode.getCallTarget().call(emptyModule, emptyModule);
    MinPklVersionChecker.check(emptyModule, importNode);
  }
}
