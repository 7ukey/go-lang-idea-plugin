/*
 * Copyright 2013-2014 Sergey Ignatov, Alexander Zolotov
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

package com.goide.psi.impl;

import com.goide.completion.GoCompletionUtil;
import com.goide.psi.*;
import com.goide.runconfig.testing.GoTestFinder;
import com.goide.sdk.GoSdkUtil;
import com.goide.util.GoUtil;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.scope.BaseScopeProcessor;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.OrderedSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.goide.psi.impl.GoPsiImplUtil.*;

public class GoReference extends PsiPolyVariantReferenceBase<GoReferenceExpressionBase> {
  public static final Key<List<PsiElement>> IMPORT_USERS = Key.create("IMPORT_USERS");

  private static final Set<String> BUILTIN_PRINT_FUNCTIONS = ContainerUtil.newHashSet("print", "println");

  private static final ResolveCache.PolyVariantResolver<PsiPolyVariantReferenceBase> MY_RESOLVER =
    new ResolveCache.PolyVariantResolver<PsiPolyVariantReferenceBase>() {
      @NotNull
      @Override
      public ResolveResult[] resolve(@NotNull PsiPolyVariantReferenceBase psiPolyVariantReferenceBase, boolean incompleteCode) {
        return ((GoReference)psiPolyVariantReferenceBase).resolveInner();
      }
    };
  public static final Key<String > ACTUAL_NAME = Key.create("ACTUAL_NAME");
  public static final Key<Object> POINTER = Key.create("POINTER");

  public GoReference(@NotNull GoReferenceExpressionBase o) {
    super(o, TextRange.from(o.getIdentifier().getStartOffsetInParent(), o.getIdentifier().getTextLength()));
  }

  @NotNull
  private ResolveResult[] resolveInner() {
    String identifierText = getName();
    Collection<ResolveResult> result = new OrderedSet<ResolveResult>();
    processResolveVariants(createResolveProcessor(identifierText, result, myElement));
    return result.toArray(new ResolveResult[result.size()]);
  }

  private String getName() {
    return myElement.getIdentifier().getText();
  }

  @NotNull
  static MyScopeProcessor createResolveProcessor(@NotNull final String text,
                                                 @NotNull final Collection<ResolveResult> result,
                                                 @NotNull final GoCompositeElement o) {
    return new MyScopeProcessor() {
      @Override
      public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
        if (element.equals(o)) return !result.add(new PsiElementResolveResult(element));
        if (element instanceof PsiNamedElement) {
          String actualName = state.get(ACTUAL_NAME);
          String name = actualName != null ? actualName : ((PsiNamedElement)element).getName();
          if (text.equals(name)) {
            result.add(new PsiElementResolveResult(element));
            return false;
          }
        }
        return true;
      }
    };
  }

  abstract static class MyScopeProcessor extends BaseScopeProcessor {
    boolean isCompletion() {
      return false;
    }
  }

  @NotNull
  static MyScopeProcessor createCompletionProcessor(@NotNull final Collection<LookupElement> variants, 
                                                    final boolean forTypes,
                                                    @NotNull final Condition<PsiElement> filter) {
    return new MyScopeProcessor() {
      @Override
      public boolean execute(@NotNull PsiElement o, @NotNull ResolveState state) {
        if (!(o instanceof GoNamedElement)) return true;
        if (((GoNamedElement)o).isBlank() || printOrPrintln(o)) return true;
        if (filter.value(o)) {
          ContainerUtil.addIfNotNull(variants, createLookup(o));
        }
        return true;
      }
      
      private boolean printOrPrintln(@NotNull PsiElement o) {
        return o instanceof GoFunctionDeclaration && BUILTIN_PRINT_FUNCTIONS.contains(((GoFunctionDeclaration)o).getName()) && builtin(o);
      }

      @Nullable
      private LookupElement createLookup(@NotNull PsiElement element) {
        // @formatter:off
        if (element instanceof GoNamedSignatureOwner)return GoCompletionUtil.createFunctionOrMethodLookupElement((GoNamedSignatureOwner)element);
        else if (element instanceof GoTypeSpec)      return forTypes ? GoCompletionUtil.createTypeLookupElement((GoTypeSpec)element) : GoCompletionUtil.createTypeConversionLookupElement((GoTypeSpec)element);
        else if (element instanceof GoImportSpec)    return GoCompletionUtil.createPackageLookupElement(((GoImportSpec)element));
        else if (element instanceof PsiDirectory)    return GoCompletionUtil.createPackageLookupElement(((PsiDirectory)element).getName(), true);
        else if (element instanceof GoNamedElement)  return GoCompletionUtil.createVariableLikeLookupElement((GoNamedElement)element);
        else if (element instanceof PsiNamedElement) return LookupElementBuilder.create((PsiNamedElement)element);
        // @formatter:on
        return null;
      }

      @Override
      boolean isCompletion() {
        return true;
      }
    };
  }

  @Override
  @NotNull
  public ResolveResult[] multiResolve(final boolean incompleteCode) {
    if (!myElement.isValid()) return ResolveResult.EMPTY_ARRAY;
    return ResolveCache.getInstance(myElement.getProject()).resolveWithCaching(this, MY_RESOLVER, false, false);
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    List<LookupElement> variants = ContainerUtil.newArrayList();
    //noinspection unchecked
    processResolveVariants(createCompletionProcessor(variants, false, Condition.TRUE));
    return ArrayUtil.toObjectArray(variants);
  }

  private boolean processResolveVariants(@NotNull MyScopeProcessor processor) {
    PsiFile file = myElement.getContainingFile();
    if (!(file instanceof GoFile)) return false;
    ResolveState state = ResolveState.initial();
    //GoReferenceExpressionBase qualifier = myElement.getQualifier();

    PsiElement parent = myElement.getParent();
    if (notSelector(myElement) || parent instanceof GoSelectorExpr && notSelector(parent) && ((GoSelectorExpr)parent).getLeft() == myElement) {
      return processUnqualifiedResolve(((GoFile)file), processor, state, true);
    }
    else if (parent instanceof GoSelectorExpr) {
      if (((GoSelectorExpr)parent).getRight() == myElement) {
        return processQualifierExpression(((GoFile)file), ((GoSelectorExpr)parent).getLeft(), processor, state);
      }
      else {
        PsiElement grand = parent.getParent();
        if (grand instanceof GoSelectorExpr && ((GoSelectorExpr)grand).getRight() == parent) {
          return processQualifierExpression(((GoFile)file), ((GoSelectorExpr)grand).getLeft(), processor, state);
          
        }
      }
    }
    


    return false;
    //return qualifier != null
    //       ? processQualifierExpression(((GoFile)file), qualifier, processor, state)
    //       : processUnqualifiedResolve(((GoFile)file), processor, state, true);
  }

  private static boolean notSelector(@NotNull PsiElement parent) {
    return !(parent.getParent() instanceof GoSelectorExpr);
  }

  private boolean processQualifierExpression(@NotNull GoFile file,
                                             @NotNull GoExpression qualifier,
                                             @NotNull MyScopeProcessor processor,
                                             @NotNull ResolveState state) {
    PsiReference reference = qualifier.getReference();
    PsiElement target = reference != null ? reference.resolve() : null;
    if (target == null || target == qualifier) {
      GoType type = qualifier.getGoType();
      if (type != null) if (!processGoType(type, processor, state)) return false;
      return false;
    }
    if (target instanceof GoImportSpec) target = ((GoImportSpec)target).getImportString().resolve();
    if (target instanceof PsiDirectory && !processDirectory((PsiDirectory)target, file, null, processor, state, false)) return false;
    if (target instanceof GoTypeOwner) {
      GoType type = parameterType((GoTypeOwner)target);
      if (type != null && !processGoType(type, processor, state)) return false;
      PsiElement parent = target.getParent();
      if (target instanceof GoVarDefinition && parent instanceof GoTypeSwitchGuard) {
        GoTypeCaseClause typeCase = PsiTreeUtil.getParentOfType(myElement, GoTypeCaseClause.class);
        GoTypeSwitchCase switchCase = typeCase != null ? typeCase.getTypeSwitchCase() : null;
        GoType caseType = switchCase != null ? switchCase.getType() : null;
        if (caseType != null && !processGoType(caseType, processor, state)) return false;
      }
    }
    
    return true;
  }

  private boolean processGoType(@NotNull GoType type, @NotNull MyScopeProcessor processor, @NotNull ResolveState state) {
    if (!processExistingType(type, processor, state)) return false;
    if (type instanceof GoPointerType) {
      if (!processPointer((GoPointerType)type, processor, state.put(POINTER, Boolean.TRUE))) return false;
      GoType pointer = type.getType();
      if (pointer instanceof GoPointerType) {
        return processPointer((GoPointerType)pointer, processor, state.put(POINTER, Boolean.TRUE));
      }
    }
    return processTypeRef(type, processor, state);
  }

  private boolean processPointer(@NotNull GoPointerType type, @NotNull MyScopeProcessor processor, @NotNull ResolveState state) {
    GoType pointer = type.getType();
    return !(pointer != null && !processExistingType(pointer, processor, state)) && processTypeRef(pointer, processor, state);
  }

  private boolean processTypeRef(@Nullable GoType type, @NotNull MyScopeProcessor processor, @NotNull ResolveState state) {
    return processInTypeRef(getTypeReference(type), type, processor, state);
  }

  private boolean processExistingType(@NotNull GoType type,
                                      @NotNull MyScopeProcessor processor,
                                      @NotNull ResolveState state
                                      ) {
    PsiFile file = type.getContainingFile();
    if (!(file instanceof GoFile)) return true;
    PsiFile myFile = myElement.getContainingFile();
    if (!(myFile instanceof GoFile)) return true;
    boolean localResolve = Comparing.equal(((GoFile)myFile).getFullPackageName(), ((GoFile)file).getFullPackageName());

    if (type instanceof GoStructType) {
      GoScopeProcessorBase delegate = createDelegate(processor);
      type.processDeclarations(delegate, ResolveState.initial(), null, myElement);
      for (GoFieldDeclaration delc : ((GoStructType)type).getFieldDeclarationList()) {
        if (!processNamedElements(processor, state, delc.getFieldDefinitionList(), localResolve)) return false;
        GoAnonymousFieldDefinition anon = delc.getAnonymousFieldDefinition();
        if (!processNamedElements(processor, state, ContainerUtil.createMaybeSingletonList(anon), localResolve)) return false;
      }
      final List<GoTypeReferenceExpression> refs = ContainerUtil.newArrayList();
      type.accept(new GoRecursiveVisitor() {
        @Override
        public void visitAnonymousFieldDefinition(@NotNull GoAnonymousFieldDefinition o) {
          refs.add(o.getTypeReferenceExpression());
        }
      });
      if (!processCollectedRefs(type, refs, processor, state)) return false;
    }
    else if (state.get(POINTER) == null && type instanceof GoInterfaceType) {
      if (!processNamedElements(processor, state, ((GoInterfaceType)type).getMethods(), localResolve)) return false;
      if (!processCollectedRefs(type, ((GoInterfaceType)type).getBaseTypesReferences(), processor, state)) return false;
    }

    PsiElement parent = type.getParent();
    if (parent instanceof GoTypeSpec && !processNamedElements(processor, state, ((GoTypeSpec)parent).getMethods(), localResolve)) {
      return false;
    }
    return true;
  }

  private boolean processCollectedRefs(@NotNull GoType type,
                                       @NotNull List<GoTypeReferenceExpression> refs,
                                       @NotNull MyScopeProcessor processor,
                                       @NotNull ResolveState state) {
    for (GoTypeReferenceExpression ref : refs) {
      if (!processInTypeRef(ref, type, processor, state)) return false;
    }
    return true;
  }

  private boolean processInTypeRef(@Nullable GoTypeReferenceExpression refExpr,
                                   @Nullable GoType recursiveStopper,
                                   @NotNull MyScopeProcessor processor,
                                   @NotNull ResolveState state) {
    PsiReference reference = refExpr != null ? refExpr.getReference() : null;
    PsiElement resolve = reference != null ? reference.resolve() : null;
    if (resolve instanceof GoTypeSpec) {
      GoType resolveType = ((GoTypeSpec)resolve).getType();
      if (resolveType != null && (recursiveStopper == null || !resolveType.textMatches(recursiveStopper)) &&
          !processGoType(resolveType, processor, state)) {
        return false;
      }
    }
    return true;
  }


  @Nullable
  private static String getPath(@Nullable PsiFile file) {
    if (file == null) return null;
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    return virtualFile == null ? null : virtualFile.getPath();
  }

  protected static boolean processDirectory(@Nullable PsiDirectory dir,
                                            @Nullable GoFile file,
                                            @Nullable String packageName,
                                            @NotNull MyScopeProcessor processor,
                                            @NotNull ResolveState state,
                                            boolean localProcessing) {
    if (dir == null) return true;
    String filePath = getPath(file);
    boolean isTesting = GoTestFinder.isTestFile(file);
    for (PsiFile f : dir.getFiles()) {
      if (f instanceof GoFile && GoUtil.allowed(f) && !Comparing.equal(getPath(f), filePath)) {
        if (GoTestFinder.isTestFile(f) && !isTesting) continue;
        if (packageName != null && !packageName.equals(((GoFile)f).getPackageName())) continue;
        if (!processFileEntities((GoFile)f, processor, state, localProcessing)) return false;
      }
    }
    return true;
  }

  private boolean processUnqualifiedResolve(@NotNull GoFile file,
                                            @NotNull MyScopeProcessor processor,
                                            @NotNull ResolveState state,
                                            boolean localResolve) {
    String id = getName();
    if ("_".equals(id)) return processor.execute(myElement, state);

    PsiElement parent = myElement.getParent();

    if (parent instanceof GoSelectorExpr) {
      boolean result = processSelector((GoSelectorExpr)parent, processor, state, myElement);
      if (processor.isCompletion()) return result;
      if (!result) return false;
    }

    PsiElement grandPa = parent.getParent();
    if (grandPa instanceof GoSelectorExpr && !processSelector((GoSelectorExpr)grandPa, processor, state, parent)) return false;

    GoScopeProcessorBase delegate = createDelegate(processor);
    ResolveUtil.treeWalkUp(myElement, delegate);
    if (!processNamedElements(processor, state, delegate.getVariants(), localResolve)) return false;
    processReceiver(delegate);
    if (!processNamedElements(processor, state, delegate.getVariants(), localResolve)) return false;
    processFunctionParameters(myElement, delegate);
    if (!processNamedElements(processor, state, delegate.getVariants(), localResolve)) return false;
    if (!processFileEntities(file, processor, state, localResolve)) return false;
    if (!processDirectory(file.getOriginalFile().getParent(), file, file.getPackageName(), processor, state, true)) return false;
    if (processImports(file, processor, state, myElement)) return false;
    if (processBuiltin(processor, state, myElement)) return false;
    return true;
  }

  static boolean processBuiltin(@NotNull MyScopeProcessor processor, @NotNull ResolveState state, @NotNull GoCompositeElement element) {
    GoFile builtinFile = GoSdkUtil.findBuiltinFile(element);
    if (builtinFile != null && !processFileEntities(builtinFile, processor, state, true)) return true;
    return false;
  }

  static boolean processImports(@NotNull GoFile file,
                                @NotNull MyScopeProcessor processor,
                                @NotNull ResolveState state,
                                @NotNull GoCompositeElement element) {
    for (Map.Entry<String, Collection<GoImportSpec>> entry : file.getImportMap().entrySet()) {
      for (GoImportSpec o : entry.getValue()) {
        GoImportString importString = o.getImportString();
        if (o.getDot() != null) {
          PsiDirectory implicitDir = importString.resolve();
          boolean resolved = !processDirectory(implicitDir, file, null, processor, state, false);
          if (resolved && !processor.isCompletion()) {
            putIfAbsent(o, element);
          }
          if (resolved) return true;
        }
        else {
          PsiDirectory resolve = importString.resolve();
          // todo: multi-resolve into appropriate package clauses
          if (resolve != null && !processor.execute(resolve, state.put(ACTUAL_NAME, entry.getKey()))) return true; 
          if (!processor.execute(o, state)) return true;
        }
      }
    }
    return false;
  }

  private boolean processSelector(@NotNull GoSelectorExpr parent,
                                  @NotNull MyScopeProcessor processor,
                                  @NotNull ResolveState state,
                                  @Nullable PsiElement another) {
    List<GoExpression> list = parent.getExpressionList();
    if (list.size() > 1 && list.get(1).isEquivalentTo(another)) {
      GoType type = list.get(0).getGoType();
      if (type != null && !processGoType(type, processor, state)) return false;
    }
    return true;
  }

  @NotNull
  private GoVarProcessor createDelegate(@NotNull MyScopeProcessor processor) {
    return new GoVarProcessor(getName(), myElement, processor.isCompletion(), true);
  }

  private static boolean processFileEntities(@NotNull GoFile file,
                                             @NotNull MyScopeProcessor processor,
                                             @NotNull ResolveState state,
                                             boolean localProcessing) {
    if (!processNamedElements(processor, state, file.getConstants(), localProcessing)) return false;
    if (!processNamedElements(processor, state, file.getVars(), localProcessing)) return false;
    if (!processNamedElements(processor, state, file.getFunctions(), localProcessing)) return false;
    if (!processNamedElements(processor, state, file.getTypes(), localProcessing)) return false;
    return true;
  }

  static boolean processNamedElements(@NotNull PsiScopeProcessor processor,
                                      @NotNull ResolveState state,
                                      @NotNull Collection<? extends GoNamedElement> elements, 
                                      boolean localResolve) {
    for (GoNamedElement definition : elements) {
      if ((localResolve || definition.isPublic()) && !processor.execute(definition, state)) return false;
    }
    return true;
  }

  // todo: return boolean for better performance 
  public static void processFunctionParameters(@NotNull GoCompositeElement e, @NotNull GoScopeProcessorBase processor) {
    GoSignatureOwner signatureOwner = PsiTreeUtil.getParentOfType(e, GoSignatureOwner.class);
    while (signatureOwner != null && processSignatureOwner(e, signatureOwner, processor)) {
      signatureOwner = PsiTreeUtil.getParentOfType(signatureOwner, GoSignatureOwner.class);
    }
  }

  private static boolean processSignatureOwner(@NotNull GoCompositeElement e,
                                               @NotNull GoSignatureOwner o,
                                               @NotNull GoScopeProcessorBase processor) {
    GoSignature signature = o.getSignature();
    if (signature == null) return true;
    if (!signature.getParameters().processDeclarations(processor, ResolveState.initial(), null, e)) return false;
    GoResult result = signature.getResult();
    GoParameters resultParameters = result != null ? result.getParameters() : null;
    if (resultParameters != null) return resultParameters.processDeclarations(processor, ResolveState.initial(), null, e);
    return true;
  }

  private void processReceiver(@NotNull GoScopeProcessorBase processor) {
    GoMethodDeclaration method = PsiTreeUtil.getParentOfType(myElement, GoMethodDeclaration.class);
    GoReceiver receiver = method != null ? method.getReceiver() : null;
    if (receiver != null) receiver.processDeclarations(processor, ResolveState.initial(), null, myElement);
  }

  @NotNull
  @Override
  public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
    myElement.getIdentifier().replace(GoElementFactory.createIdentifierFromText(myElement.getProject(), newElementName));
    return myElement;
  }

  static void putIfAbsent(@NotNull PsiElement importElement, @NotNull PsiElement usage) {
    List<PsiElement> list = importElement.getUserData(IMPORT_USERS);
    if (list == null) list = ContainerUtil.newArrayListWithCapacity(1);
    list.add(usage);
    importElement.putUserData(IMPORT_USERS, list);
  }
}