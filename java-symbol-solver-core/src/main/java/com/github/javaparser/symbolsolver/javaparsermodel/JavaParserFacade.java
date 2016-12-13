/*
 * Copyright 2016 Federico Tomassetti
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

package com.github.javaparser.symbolsolver.javaparsermodel;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.symbolsolver.core.resolution.Context;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.*;
import com.github.javaparser.symbolsolver.logic.FunctionalInterfaceLogic;
import com.github.javaparser.symbolsolver.logic.InferenceContext;
import com.github.javaparser.symbolsolver.model.declarations.*;
import com.github.javaparser.symbolsolver.model.declarations.ConstructorDeclaration;
import com.github.javaparser.symbolsolver.model.declarations.MethodDeclaration;
import com.github.javaparser.symbolsolver.model.declarations.TypeDeclaration;
import com.github.javaparser.symbolsolver.model.methods.MethodUsage;
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.model.resolution.Value;
import com.github.javaparser.symbolsolver.model.typesystem.*;
import com.github.javaparser.symbolsolver.reflectionmodel.MyObjectProvider;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionClassDeclaration;
import com.github.javaparser.symbolsolver.resolution.ConstructorResolutionLogic;
import com.github.javaparser.symbolsolver.resolution.SymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.common.collect.ImmutableList;

import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.github.javaparser.symbolsolver.javaparser.Navigator.getParentNode;

/**
 * Class to be used by final users to solve symbols for JavaParser ASTs.
 *
 * @author Federico Tomassetti
 */
public class JavaParserFacade {

    private static Logger logger = Logger.getLogger(JavaParserFacade.class.getCanonicalName());

    static {
        logger.setLevel(Level.INFO);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        logger.addHandler(consoleHandler);
    }

    private static Map<TypeSolver, JavaParserFacade> instances = new HashMap<>();
    private TypeSolver typeSolver;
    private SymbolSolver symbolSolver;
    private Map<Node, Type> cacheWithLambdasSolved = new IdentityHashMap<>();
    private Map<Node, Type> cacheWithoutLambadsSolved = new IdentityHashMap<>();

    private JavaParserFacade(TypeSolver typeSolver) {
        this.typeSolver = typeSolver.getRoot();
        this.symbolSolver = new SymbolSolver(typeSolver);
    }

    public static JavaParserFacade get(TypeSolver typeSolver) {
        if (!instances.containsKey(typeSolver)) {
            instances.put(typeSolver, new JavaParserFacade(typeSolver));
        }
        return instances.get(typeSolver);
    }

    /**
     * This method is used to clear internal caches for the sake of releasing memory.
     */
    public static void clearInstances() {
        instances.clear();
    }

    private static Type solveGenericTypes(Type type, Context context, TypeSolver typeSolver) {
        if (type.isTypeVariable()) {
            Optional<Type> solved = context.solveGenericType(type.describe(), typeSolver);
            if (solved.isPresent()) {
                return solved.get();
            } else {
                return type;
            }
        } else if (type.isWildcard()) {
            if (type.asWildcard().isExtends() || type.asWildcard().isSuper()) {
                Wildcard wildcardUsage = type.asWildcard();
                Type boundResolved = solveGenericTypes(wildcardUsage.getBoundedType(), context, typeSolver);
                if (wildcardUsage.isExtends()) {
                    return Wildcard.extendsBound(boundResolved);
                } else {
                    return Wildcard.superBound(boundResolved);
                }
            } else {
                return type;
            }
        } else {
            Type result = type;
            return result;
        }
    }

    public SymbolReference<? extends ValueDeclaration> solve(NameExpr nameExpr) {
        return symbolSolver.solveSymbol(nameExpr.getName().getId(), nameExpr);
    }

    public SymbolReference<? extends ValueDeclaration> solve(SimpleName nameExpr) {
        return symbolSolver.solveSymbol(nameExpr.getId(), nameExpr);
    }

    public SymbolReference<? extends ValueDeclaration> solve(Expression expr) {
        if (expr instanceof NameExpr) {
            return solve((NameExpr) expr);
        } else {
            throw new IllegalArgumentException(expr.getClass().getCanonicalName());
        }
    }

    public SymbolReference<MethodDeclaration> solve(MethodCallExpr methodCallExpr) {
        return solve(methodCallExpr, true);
    }

    public SymbolReference<ConstructorDeclaration> solve(ObjectCreationExpr objectCreationExpr) {
        return solve(objectCreationExpr, true);
    }

    public SymbolReference<ConstructorDeclaration> solve(ExplicitConstructorInvocationStmt explicitConstructorInvocationStmt) {
        return solve(explicitConstructorInvocationStmt, true);
    }

    public SymbolReference<ConstructorDeclaration> solve(ExplicitConstructorInvocationStmt explicitConstructorInvocationStmt, boolean solveLambdas) {
        List<Type> argumentTypes = new LinkedList<>();
        List<LambdaArgumentTypePlaceholder> placeholders = new LinkedList<>();

        solveArguments(explicitConstructorInvocationStmt, explicitConstructorInvocationStmt.getArgs(), solveLambdas, argumentTypes, placeholders);

        ClassOrInterfaceDeclaration classNode = explicitConstructorInvocationStmt.getAncestorOfType(ClassOrInterfaceDeclaration.class);
        if (classNode == null) {
            return SymbolReference.unsolved(ConstructorDeclaration.class);
        }
        TypeDeclaration typeDecl = null;
        if (!explicitConstructorInvocationStmt.isThis()) {
            Type classDecl = JavaParserFacade.get(typeSolver).convert(classNode.getExtends(0), classNode);
            if (classDecl.isReferenceType()) {
                typeDecl = classDecl.asReferenceType().getTypeDeclaration();
            }
        } else {
            SymbolReference<TypeDeclaration> sr = JavaParserFactory.getContext(classNode, typeSolver).solveType(classNode.getNameAsString(), typeSolver);
            if (sr.isSolved()) {
                typeDecl = sr.getCorrespondingDeclaration();
            }
        }
        if (typeDecl == null) {
            return SymbolReference.unsolved(ConstructorDeclaration.class);
        }
        SymbolReference<ConstructorDeclaration> res = ConstructorResolutionLogic.findMostApplicable(((ClassDeclaration) typeDecl).getConstructors(), argumentTypes, typeSolver);
        for (LambdaArgumentTypePlaceholder placeholder : placeholders) {
            placeholder.setMethod(res);
        }
        return res;
    }

    /**
     * Given a constructor call find out to which constructor declaration it corresponds.
     */
    public SymbolReference<ConstructorDeclaration> solve(ObjectCreationExpr objectCreationExpr, boolean solveLambdas) {
        List<Type> argumentTypes = new LinkedList<>();
        List<LambdaArgumentTypePlaceholder> placeholders = new LinkedList<>();

        solveArguments(objectCreationExpr, objectCreationExpr.getArgs(), solveLambdas, argumentTypes, placeholders);

        Type classDecl = JavaParserFacade.get(typeSolver).convert(objectCreationExpr.getType(), objectCreationExpr);
        if (!classDecl.isReferenceType()) {
            return SymbolReference.unsolved(ConstructorDeclaration.class);
        }
        SymbolReference<ConstructorDeclaration> res = ConstructorResolutionLogic.findMostApplicable(((ClassDeclaration) classDecl.asReferenceType().getTypeDeclaration()).getConstructors(), argumentTypes, typeSolver);
        for (LambdaArgumentTypePlaceholder placeholder : placeholders) {
            placeholder.setMethod(res);
        }
        return res;
    }

    private void solveArguments(Node node, NodeList<Expression> args, boolean solveLambdas, List<Type> argumentTypes, List<LambdaArgumentTypePlaceholder> placeholders) {
        int i = 0;
        for (Expression parameterValue : args) {
            if (parameterValue instanceof LambdaExpr || parameterValue instanceof MethodReferenceExpr) {
                LambdaArgumentTypePlaceholder placeholder = new LambdaArgumentTypePlaceholder(i);
                argumentTypes.add(placeholder);
                placeholders.add(placeholder);
            } else {
                try {
                    argumentTypes.add(JavaParserFacade.get(typeSolver).getType(parameterValue, solveLambdas));
                } catch (UnsolvedSymbolException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(String.format("Unable to calculate the type of a parameter of a method call. Method call: %s, Parameter: %s",
                            node, parameterValue), e);
                }
            }
            i++;
        }
    }

    /**
     * Given a method call find out to which method declaration it corresponds.
     */
    public SymbolReference<MethodDeclaration> solve(MethodCallExpr methodCallExpr, boolean solveLambdas) {
        List<Type> argumentTypes = new LinkedList<>();
        List<LambdaArgumentTypePlaceholder> placeholders = new LinkedList<>();

        solveArguments(methodCallExpr, methodCallExpr.getArgs(), solveLambdas, argumentTypes, placeholders);

        SymbolReference<MethodDeclaration> res = JavaParserFactory.getContext(methodCallExpr, typeSolver).solveMethod(methodCallExpr.getName().getId(), argumentTypes, typeSolver);
        for (LambdaArgumentTypePlaceholder placeholder : placeholders) {
            placeholder.setMethod(res);
        }
        return res;
    }

    public Type getType(Node node) {
        return getType(node, true);
    }

    public Type getType(Node node, boolean solveLambdas) {
        if (solveLambdas) {
            if (!cacheWithLambdasSolved.containsKey(node)) {
                Type res = getTypeConcrete(node, solveLambdas);

                cacheWithLambdasSolved.put(node, res);

                boolean secondPassNecessary = false;
                if (node instanceof MethodCallExpr) {
                    MethodCallExpr methodCallExpr = (MethodCallExpr) node;
                    for (Node arg : methodCallExpr.getArgs()) {
                        if (!cacheWithLambdasSolved.containsKey(arg)) {
                            getType(arg, true);
                            secondPassNecessary = true;
                        }
                    }
                }
                if (secondPassNecessary) {
                    cacheWithLambdasSolved.remove(node);
                    cacheWithLambdasSolved.put(node, getType(node, true));
                }
                logger.finer("getType on " + node + " -> " + res);
            }
            return cacheWithLambdasSolved.get(node);
        } else {
            Optional<Type> res = find(cacheWithLambdasSolved, node);
            if (res.isPresent()) {
                return res.get();
            }
            res = find(cacheWithoutLambadsSolved, node);
            if (!res.isPresent()) {
                Type resType = getTypeConcrete(node, solveLambdas);
                cacheWithoutLambadsSolved.put(node, resType);
                logger.finer("getType on " + node + " (no solveLambdas) -> " + res);
                return resType;
            }
            return res.get();
        }
    }

    private Optional<Type> find(Map<Node, Type> map, Node node) {
        if (map.containsKey(node)) {
            return Optional.of(map.get(node));
        }
        if (node instanceof LambdaExpr) {
            return find(map, (LambdaExpr) node);
        } else {
            return Optional.empty();
        }
    }

    /**
     * For some reasons LambdaExprs are duplicate and the equals method is not implemented correctly.
     *
     * @param map
     * @return
     */
    private Optional<Type> find(Map<Node, Type> map, LambdaExpr lambdaExpr) {
        for (Node key : map.keySet()) {
            if (key instanceof LambdaExpr) {
                LambdaExpr keyLambdaExpr = (LambdaExpr) key;
                if (keyLambdaExpr.toString().equals(lambdaExpr.toString()) && getParentNode(keyLambdaExpr) == getParentNode(lambdaExpr)) {
                    return Optional.of(map.get(keyLambdaExpr));
                }
            }
        }
        return Optional.empty();
    }

    private MethodUsage toMethodUsage(MethodReferenceExpr methodReferenceExpr) {
        if (!(methodReferenceExpr.getScope() instanceof TypeExpr)) {
            throw new UnsupportedOperationException();
        }
        TypeExpr typeExpr = (TypeExpr) methodReferenceExpr.getScope();
        if (!(typeExpr.getType() instanceof com.github.javaparser.ast.type.ClassOrInterfaceType)) {
            throw new UnsupportedOperationException(typeExpr.getType().getClass().getCanonicalName());
        }
        ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType) typeExpr.getType();
        SymbolReference<TypeDeclaration> typeDeclarationSymbolReference = JavaParserFactory.getContext(classOrInterfaceType, typeSolver).solveType(classOrInterfaceType.getName().getId(), typeSolver);
        if (!typeDeclarationSymbolReference.isSolved()) {
            throw new UnsupportedOperationException();
        }
        List<MethodUsage> methodUsages = ((ReferenceTypeDeclaration) typeDeclarationSymbolReference.getCorrespondingDeclaration()).getAllMethods().stream().filter(it -> it.getName().equals(methodReferenceExpr.getIdentifier())).collect(Collectors.toList());
        switch (methodUsages.size()) {
            case 0:
                throw new UnsupportedOperationException();
            case 1:
                return methodUsages.get(0);
            default:
                throw new UnsupportedOperationException();
        }
    }

    private Type getBinaryTypeConcrete(Node left, Node right, boolean solveLambdas) {
        Type leftType = getTypeConcrete(left, solveLambdas);
        Type rightType = getTypeConcrete(right, solveLambdas);
        if (rightType.isAssignableBy(leftType)) {
            return rightType;
        }
        return leftType;
    }


    /**
     * Should return more like a TypeApplication: a TypeDeclaration and possible typeParametersValues or array
     * modifiers.
     *
     * @return
     */
    private Type getTypeConcrete(Node node, boolean solveLambdas) {
        if (node == null) throw new IllegalArgumentException();
        if (node instanceof NameExpr) {
            NameExpr nameExpr = (NameExpr) node;
            logger.finest("getType on name expr " + node);
            Optional<Value> value = new SymbolSolver(typeSolver).solveSymbolAsValue(nameExpr.getName().getId(), nameExpr);
            if (!value.isPresent()) {
                throw new UnsolvedSymbolException("Solving " + node, nameExpr.getName().getId());
            } else {
                return value.get().getType();
            }
        } else if (node instanceof MethodCallExpr) {
            logger.finest("getType on method call " + node);
            // first solve the method
            MethodUsage ref = solveMethodAsUsage((MethodCallExpr) node);
            logger.finest("getType on method call " + node + " resolved to " + ref);
            logger.finest("getType on method call " + node + " return type is " + ref.returnType());
            return ref.returnType();
            // the type is the return type of the method
        } else if (node instanceof LambdaExpr) {
            if (getParentNode(node) instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) getParentNode(node);
                int pos = JavaParserSymbolDeclaration.getParamPos(node);
                SymbolReference<MethodDeclaration> refMethod = JavaParserFacade.get(typeSolver).solve(callExpr);
                if (!refMethod.isSolved()) {
                    throw new UnsolvedSymbolException(getParentNode(node).toString(), callExpr.getName().getId());
                }
                logger.finest("getType on lambda expr " + refMethod.getCorrespondingDeclaration().getName());
                //logger.finest("Method param " + refMethod.getCorrespondingDeclaration().getParam(pos));
                if (solveLambdas) {

                    // The type parameter referred here should be the java.util.stream.Stream.T
                    Type result = refMethod.getCorrespondingDeclaration().getParam(pos).getType();

                    // FIXME: here we should replace the type parameters that can be resolved
                    //        for example when invoking myListOfStrings.stream().filter(s -> s.length > 0);
                    //        the MethodDeclaration of filter is:
                    //        Stream<T> filter(Predicate<? super T> predicate)
                    //        but T in this case is equal to String
                    if (callExpr.getScope() != null) {

                        // If it is a static call we should not try to get the type of the scope
                        boolean staticCall = false;
                        if (callExpr.getScope() instanceof NameExpr) {
                            NameExpr nameExpr = (NameExpr) callExpr.getScope();
                            try {
                                JavaParserFactory.getContext(nameExpr, typeSolver).solveType(nameExpr.getName().getId(), typeSolver);
                                staticCall = true;
                            } catch (Exception e) {

                            }
                        }

                        if (!staticCall) {
                            Type scopeType = JavaParserFacade.get(typeSolver).getType(callExpr.getScope());
                            if (scopeType.isReferenceType()) {
                                result = scopeType.asReferenceType().useThisTypeParametersOnTheGivenType(result);
                            }
                        }
                    }

                    // We need to replace the type variables
                    Context ctx = JavaParserFactory.getContext(node, typeSolver);
                    result = solveGenericTypes(result, ctx, typeSolver);

                    //We should find out which is the functional method (e.g., apply) and replace the params of the
                    //solveLambdas with it, to derive so the values. We should also consider the value returned by the
                    //lambdas
                    Optional<MethodUsage> functionalMethod = FunctionalInterfaceLogic.getFunctionalMethod(result);
                    if (functionalMethod.isPresent()) {
                        LambdaExpr lambdaExpr = (LambdaExpr) node;

                        InferenceContext inferenceContext = new InferenceContext(MyObjectProvider.INSTANCE);
                        // At this point parameterType
                        // if Function<T=? super Stream.T, ? extends map.R>
                        // we should replace Stream.T
                        Type functionalInterfaceType = ReferenceTypeImpl.undeterminedParameters(functionalMethod.get().getDeclaration().declaringType(), typeSolver);
                        //inferenceContext.addPair(parameterType, functionalInterfaceType);
                        //inferenceContext.addPair(parameterType, result);
                        inferenceContext.addPair(result, functionalInterfaceType);
                        if (lambdaExpr.getBody() instanceof ExpressionStmt) {
                            ExpressionStmt expressionStmt = (ExpressionStmt) lambdaExpr.getBody();
                            Type actualType = getType(expressionStmt.getExpression());
                            Type formalType = functionalMethod.get().returnType();
                            inferenceContext.addPair(formalType, actualType);
                            result = inferenceContext.resolve(inferenceContext.addSingle(result));
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }

                    return result;
                } else {
                    return refMethod.getCorrespondingDeclaration().getParam(pos).getType();
                }
            } else {
                throw new UnsupportedOperationException("The type of a lambda expr depends on the position and its return value");
            }
        } else if (node instanceof MethodReferenceExpr) {
            if (getParentNode(node) instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) getParentNode(node);
                int pos = JavaParserSymbolDeclaration.getParamPos(node);
                SymbolReference<MethodDeclaration> refMethod = JavaParserFacade.get(typeSolver).solve(callExpr, false);
                if (!refMethod.isSolved()) {
                    throw new UnsolvedSymbolException(getParentNode(node).toString(), callExpr.getName().getId());
                }
                logger.finest("getType on method reference expr " + refMethod.getCorrespondingDeclaration().getName());
                //logger.finest("Method param " + refMethod.getCorrespondingDeclaration().getParam(pos));
                if (solveLambdas) {
                    Type result = refMethod.getCorrespondingDeclaration().getParam(pos).getType();
                    // We need to replace the type variables
                    Context ctx = JavaParserFactory.getContext(node, typeSolver);
                    result = solveGenericTypes(result, ctx, typeSolver);

                    //We should find out which is the functional method (e.g., apply) and replace the params of the
                    //solveLambdas with it, to derive so the values. We should also consider the value returned by the
                    //lambdas
                    Optional<MethodUsage> functionalMethod = FunctionalInterfaceLogic.getFunctionalMethod(result);
                    if (functionalMethod.isPresent()) {
                        if (node instanceof MethodReferenceExpr) {
                            MethodReferenceExpr methodReferenceExpr = (MethodReferenceExpr) node;

                            Type actualType = toMethodUsage(methodReferenceExpr).returnType();
                            Type formalType = functionalMethod.get().returnType();

                            InferenceContext inferenceContext = new InferenceContext(MyObjectProvider.INSTANCE);
                            inferenceContext.addPair(formalType, actualType);
                            result = inferenceContext.resolve(inferenceContext.addSingle(result));
                        } else {
                            LambdaExpr lambdaExpr = (LambdaExpr) node;

                            if (lambdaExpr.getBody() instanceof ExpressionStmt) {
                                ExpressionStmt expressionStmt = (ExpressionStmt) lambdaExpr.getBody();
                                Type actualType = getType(expressionStmt.getExpression());
                                Type formalType = functionalMethod.get().returnType();

                                InferenceContext inferenceContext = new InferenceContext(MyObjectProvider.INSTANCE);
                                inferenceContext.addPair(formalType, actualType);
                                result = inferenceContext.resolve(inferenceContext.addSingle(result));
                            } else {
                                throw new UnsupportedOperationException();
                            }
                        }
                    }

                    return result;
                } else {
                    return refMethod.getCorrespondingDeclaration().getParam(pos).getType();
                }
            } else {
                throw new UnsupportedOperationException("The type of a method reference expr depends on the position and its return value");
            }
        } else if (node instanceof VariableDeclarator) {
            if (getParentNode(node) instanceof FieldDeclaration) {
//                FieldDeclaration parent = (FieldDeclaration) getParentNode(node);
                return JavaParserFacade.get(typeSolver).convertToUsageVariableType((VariableDeclarator) node);
            } else if (getParentNode(node) instanceof VariableDeclarationExpr) {
//                VariableDeclarationExpr parent = (VariableDeclarationExpr) getParentNode(node);
                return JavaParserFacade.get(typeSolver).convertToUsageVariableType((VariableDeclarator) node);
            } else {
                throw new UnsupportedOperationException(getParentNode(node).getClass().getCanonicalName());
            }
        } else if (node instanceof Parameter) {
            Parameter parameter = (Parameter) node;
            if (parameter.getType() instanceof UnknownType) {
                throw new IllegalStateException("Parameter has unknown type: " + parameter);
            }
            return JavaParserFacade.get(typeSolver).convertToUsage(parameter.getType(), parameter);
        } else if (node instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) node;
            // We should understand if this is a static access
            if (fieldAccessExpr.getScope().isPresent() && fieldAccessExpr.getScope().get() instanceof NameExpr) {
                NameExpr staticValue = (NameExpr) fieldAccessExpr.getScope().get();
                SymbolReference<TypeDeclaration> typeAccessedStatically = JavaParserFactory.getContext(fieldAccessExpr, typeSolver).solveType(staticValue.toString(), typeSolver);
                if (typeAccessedStatically.isSolved()) {
                    // TODO here maybe we have to substitute type typeParametersValues
                    return ((ReferenceTypeDeclaration) typeAccessedStatically.getCorrespondingDeclaration()).getField(fieldAccessExpr.getField().getId()).getType();
                }
            } else if (fieldAccessExpr.getScope().isPresent() && fieldAccessExpr.getScope().get().toString().indexOf('.') > 0) {
                // try to find fully qualified name
                SymbolReference<ReferenceTypeDeclaration> sr = typeSolver.tryToSolveType(fieldAccessExpr.getScope().get().toString());
                if (sr.isSolved()) {
                    return sr.getCorrespondingDeclaration().getField(fieldAccessExpr.getField().getId()).getType();
                }
            }
            Optional<Value> value = null;
            try {
                value = new SymbolSolver(typeSolver).solveSymbolAsValue(fieldAccessExpr.getField().getId(), fieldAccessExpr);
            } catch (UnsolvedSymbolException use) {
                // Deal with badly parsed FieldAccessExpr that are in fact fqn classes
                if (fieldAccessExpr.getParentNode().isPresent() && fieldAccessExpr.getParentNode().get() instanceof FieldAccessExpr) {
                    throw use;
                }
                SymbolReference<ReferenceTypeDeclaration> sref = typeSolver.tryToSolveType(node.toString());
                if (sref.isSolved()) {
                    return new ReferenceTypeImpl(sref.getCorrespondingDeclaration(), typeSolver);
                }
            }
            if (value != null && value.isPresent()) {
                return value.get().getType();
            } else {
                throw new UnsolvedSymbolException(fieldAccessExpr.getField().getId());
            }
        } else if (node instanceof ObjectCreationExpr) {
            ObjectCreationExpr objectCreationExpr = (ObjectCreationExpr) node;
            Type type = JavaParserFacade.get(typeSolver).convertToUsage(objectCreationExpr.getType(), node);
            return type;
        } else if (node instanceof NullLiteralExpr) {
            return NullType.INSTANCE;
        } else if (node instanceof BooleanLiteralExpr) {
            return PrimitiveType.BOOLEAN;
        } else if (node instanceof IntegerLiteralExpr) {
            return PrimitiveType.INT;
        } else if (node instanceof LongLiteralExpr) {
            return PrimitiveType.LONG;
        } else if (node instanceof CharLiteralExpr) {
            return PrimitiveType.CHAR;
        } else if (node instanceof DoubleLiteralExpr) {
            if (((DoubleLiteralExpr) node).getValue().toLowerCase().endsWith("f")) {
                return PrimitiveType.FLOAT;
            }
            return PrimitiveType.DOUBLE;
        } else if (node instanceof StringLiteralExpr) {
            return new ReferenceTypeImpl(new ReflectionTypeSolver().solveType("java.lang.String"), typeSolver);
        } else if (node instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) node;
            switch (unaryExpr.getOperator()) {
                case negative:
                case positive:
                    return getTypeConcrete(unaryExpr.getExpr(), solveLambdas);
                case not:
                    return PrimitiveType.BOOLEAN;
                case postIncrement:
                case preIncrement:
                case preDecrement:
                case postDecrement:
                    return getTypeConcrete(unaryExpr.getExpr(), solveLambdas);
                default:
                    throw new UnsupportedOperationException(unaryExpr.getOperator().name());
            }
        } else if (node instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) node;
            switch (binaryExpr.getOperator()) {
                case plus:
                case minus:
                case divide:
                case times:
                    return getBinaryTypeConcrete(binaryExpr.getLeft(), binaryExpr.getRight(), solveLambdas);
                case lessEquals:
                case less:
                case greater:
                case greaterEquals:
                case equals:
                case notEquals:
                case or:
                case and:
                    return PrimitiveType.BOOLEAN;
                case binAnd:
                case binOr:
                case rSignedShift:
                case rUnsignedShift:
                case lShift:
                case remainder:
                case xor:
                    return getTypeConcrete(binaryExpr.getLeft(), solveLambdas);
                default:
                    throw new UnsupportedOperationException("FOO " + binaryExpr.getOperator().name());
            }
        } else if (node instanceof VariableDeclarationExpr) {
            VariableDeclarationExpr expr = (VariableDeclarationExpr) node;
            if (expr.getVariables().size() != 1) {
                throw new UnsupportedOperationException();
            }
            return convertToUsageVariableType(expr.getVariables().get(0));
        } else if (node instanceof InstanceOfExpr) {
            return PrimitiveType.BOOLEAN;
        } else if (node instanceof EnclosedExpr) {
            EnclosedExpr enclosedExpr = (EnclosedExpr) node;
            return getTypeConcrete(enclosedExpr.getInner().get(), solveLambdas);
        } else if (node instanceof CastExpr) {
            CastExpr enclosedExpr = (CastExpr) node;
            return convertToUsage(enclosedExpr.getType(), JavaParserFactory.getContext(node, typeSolver));
        } else if (node instanceof AssignExpr) {
            AssignExpr assignExpr = (AssignExpr) node;
            return getTypeConcrete(assignExpr.getTarget(), solveLambdas);
        } else if (node instanceof ThisExpr) {
            return new ReferenceTypeImpl(getTypeDeclaration(findContainingTypeDecl(node)), typeSolver);
        } else if (node instanceof ConditionalExpr) {
            ConditionalExpr conditionalExpr = (ConditionalExpr) node;
            return getTypeConcrete(conditionalExpr.getThenExpr(), solveLambdas);
        } else if (node instanceof ArrayCreationExpr) {
            ArrayCreationExpr arrayCreationExpr = (ArrayCreationExpr) node;
            Type res = convertToUsage(arrayCreationExpr.getType(), JavaParserFactory.getContext(node, typeSolver));
            return res;
        } else if (node instanceof ArrayAccessExpr) {
            ArrayAccessExpr arrayAccessExpr = (ArrayAccessExpr) node;
            Type arrayUsageType = getTypeConcrete(arrayAccessExpr.getName(), solveLambdas);
            if (arrayUsageType.isArray()) {
                return ((ArrayType) arrayUsageType).getComponentType();
            }
            return arrayUsageType;
        } else if (node instanceof SuperExpr) {
            TypeDeclaration typeOfNode = getTypeDeclaration(findContainingTypeDecl(node));
            if (typeOfNode instanceof ClassDeclaration) {
                return ((ClassDeclaration) typeOfNode).getSuperClass();
            } else {
                throw new UnsupportedOperationException(node.getClass().getCanonicalName());
            }
        } else if (node instanceof ClassExpr) {
            // This implementation does not regard the actual type argument of the ClassExpr.
            ClassExpr classExpr = (ClassExpr) node;
            com.github.javaparser.ast.type.Type<?> astType = classExpr.getType();
            Type jssType = convertToUsage(astType, classExpr.getType());
            return new ReferenceTypeImpl(new ReflectionClassDeclaration(Class.class, typeSolver), ImmutableList.of(jssType), typeSolver);
        } else {
            throw new UnsupportedOperationException(node.getClass().getCanonicalName());
        }
    }

    private com.github.javaparser.ast.body.TypeDeclaration<?> findContainingTypeDecl(Node node) {
        if (node instanceof ClassOrInterfaceDeclaration) {
            return (ClassOrInterfaceDeclaration) node;
        } else if (node instanceof EnumDeclaration) {
            return (EnumDeclaration) node;
        } else if (getParentNode(node) == null) {
            throw new IllegalArgumentException();
        } else {
            return findContainingTypeDecl(getParentNode(node));
        }
    }

    public Type convertToUsageVariableType(VariableDeclarator var) {
        Type type = JavaParserFacade.get(typeSolver).convertToUsage(var.getType(), var);
        return type;
    }

    public Type convertToUsage(com.github.javaparser.ast.type.Type<?> type, Node context) {
        if (type instanceof UnknownType) {
            throw new IllegalArgumentException("Unknown type");
        }
        return convertToUsage(type, JavaParserFactory.getContext(context, typeSolver));
    }

    // This is an hack around an issue in JavaParser
    private String qName(ClassOrInterfaceType classOrInterfaceType) {
        String name = classOrInterfaceType.getName().getId();
        if (classOrInterfaceType.getScope().isPresent()) {
            return qName(classOrInterfaceType.getScope().get()) + "." + name;
        } else {
            return name;
        }
    }

    private Type convertToUsage(com.github.javaparser.ast.type.Type<?> type, Context context) {
        if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType) type;
            String name = qName(classOrInterfaceType);
            SymbolReference<TypeDeclaration> ref = context.solveType(name, typeSolver);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(name);
            }
            TypeDeclaration typeDeclaration = ref.getCorrespondingDeclaration();
            List<Type> typeParameters = Collections.emptyList();
            if (classOrInterfaceType.getTypeArguments().isPresent()) {
                typeParameters = classOrInterfaceType.getTypeArguments().get().stream().map((pt) -> convertToUsage(pt, context)).collect(Collectors.toList());
            }
            if (typeDeclaration.isTypeParameter()) {
                if (typeDeclaration instanceof TypeParameterDeclaration) {
                    return new TypeVariable((TypeParameterDeclaration) typeDeclaration);
                } else {
                    JavaParserTypeVariableDeclaration javaParserTypeVariableDeclaration = (JavaParserTypeVariableDeclaration) typeDeclaration;
                    return new TypeVariable(javaParserTypeVariableDeclaration.asTypeParameter());
                }
            } else {
                return new ReferenceTypeImpl((ReferenceTypeDeclaration) typeDeclaration, typeParameters, typeSolver);
            }
        } else if (type instanceof com.github.javaparser.ast.type.PrimitiveType) {
            return PrimitiveType.byName(((com.github.javaparser.ast.type.PrimitiveType) type).getType().name());
        } else if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            if (wildcardType.getExtends().isPresent() && !wildcardType.getSuper().isPresent()) {
                return Wildcard.extendsBound(convertToUsage(wildcardType.getExtends().get(), context)); // removed (ReferenceTypeImpl) 
            } else if (!wildcardType.getExtends().isPresent() && wildcardType.getSuper().isPresent()) {
                return Wildcard.extendsBound(convertToUsage(wildcardType.getSuper().get(), context)); // removed (ReferenceTypeImpl) 
            } else if (!wildcardType.getExtends().isPresent() && !wildcardType.getSuper().isPresent()) {
                return Wildcard.UNBOUNDED;
            } else {
                throw new UnsupportedOperationException(wildcardType.toString());
            }
        } else if (type instanceof com.github.javaparser.ast.type.VoidType) {
            return VoidType.INSTANCE;
        } else if (type instanceof com.github.javaparser.ast.type.ArrayType) {
            com.github.javaparser.ast.type.ArrayType jpArrayType = (com.github.javaparser.ast.type.ArrayType) type;
            return new ArrayType(convertToUsage(jpArrayType.getComponentType(), context));
        } else {
            throw new UnsupportedOperationException(type.getClass().getCanonicalName());
        }
    }


    public Type convert(com.github.javaparser.ast.type.Type<?> type, Node node) {
        return convert(type, JavaParserFactory.getContext(node, typeSolver));
    }

    public Type convert(com.github.javaparser.ast.type.Type<?> type, Context context) {
        return convertToUsage(type, context);
    }

    public MethodUsage solveMethodAsUsage(MethodCallExpr call) {
        List<Type> params = new ArrayList<>();
        if (call.getArgs() != null) {
            for (Expression param : call.getArgs()) {
                //getTypeConcrete(Node node, boolean solveLambdas)
                try {
                    params.add(getType(param, false));
                } catch (Exception e) {
                    throw new RuntimeException(String.format("Error calculating the type of parameter %s of method call %s", param, call), e);
                }
                //params.add(getTypeConcrete(param, false));
            }
        }
        Context context = JavaParserFactory.getContext(call, typeSolver);
        Optional<MethodUsage> methodUsage = context.solveMethodAsUsage(call.getName().getId(), params, typeSolver);
        if (!methodUsage.isPresent()) {
            throw new RuntimeException("Method '" + call.getName() + "' cannot be resolved in context "
                    + call + " (line: " + call.getRange().begin.line + ") " + context + ". Parameter types: " + params);
        }
        return methodUsage.get();
    }

    public ReferenceTypeDeclaration getTypeDeclaration(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        if (classOrInterfaceDeclaration.isInterface()) {
            return new JavaParserInterfaceDeclaration(classOrInterfaceDeclaration, typeSolver);
        } else {
            return new JavaParserClassDeclaration(classOrInterfaceDeclaration, typeSolver);
        }
    }

    /**
     * "this" inserted in the given point, which type would have?
     */
    public Type getTypeOfThisIn(Node node) {
        // TODO consider static methods
        if (node instanceof ClassOrInterfaceDeclaration) {
            return new ReferenceTypeImpl(getTypeDeclaration((ClassOrInterfaceDeclaration) node), typeSolver);
        } else if (node instanceof EnumDeclaration) {
            JavaParserEnumDeclaration enumDeclaration = new JavaParserEnumDeclaration((EnumDeclaration) node, typeSolver);
            return new ReferenceTypeImpl(enumDeclaration, typeSolver);
        } else {
            return getTypeOfThisIn(getParentNode(node));
        }
    }

    public ReferenceTypeDeclaration getTypeDeclaration(com.github.javaparser.ast.body.TypeDeclaration<?> typeDeclaration) {
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration) {
            return getTypeDeclaration((ClassOrInterfaceDeclaration) typeDeclaration);
        } else if (typeDeclaration instanceof EnumDeclaration) {
            return new JavaParserEnumDeclaration((EnumDeclaration) typeDeclaration, typeSolver);
        } else {
            throw new UnsupportedOperationException(typeDeclaration.getClass().getCanonicalName());
        }
    }
}
