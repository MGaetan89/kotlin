/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import org.jetbrains.kotlin.analysis.api.components.KaBuiltinTypes
import org.jetbrains.kotlin.analysis.api.components.KaTypeProvider
import org.jetbrains.kotlin.analysis.api.fir.KaFirSession
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.dispatchReceiverType
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirType
import org.jetbrains.kotlin.analysis.api.fir.types.PublicTypeApproximator
import org.jetbrains.kotlin.analysis.api.fir.utils.toConeNullability
import org.jetbrains.kotlin.analysis.api.impl.base.components.KaSessionComponent
import org.jetbrains.kotlin.analysis.api.lifetime.KaLifetimeToken
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeNullability
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFir
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirFile
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirOfType
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.resolveToFirSymbolOfTypeSafe
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.throwUnexpectedFirElementError
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.llFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.ContextCollector
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.ConeTypeCompatibilityChecker
import org.jetbrains.kotlin.fir.analysis.checkers.ConeTypeCompatibilityChecker.isCompatible
import org.jetbrains.kotlin.fir.analysis.checkers.typeParameterSymbols
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.fullyExpandedClass
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.java.enhancement.EnhancedForWarningConeSubstitutor
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.resolve.SessionHolderImpl
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.types.model.CaptureStatus
import org.jetbrains.kotlin.util.bfs
import org.jetbrains.kotlin.utils.exceptions.errorWithAttachment
import org.jetbrains.kotlin.utils.exceptions.withPsiEntry

internal class KaFirTypeProvider(
    override val analysisSessionProvider: () -> KaFirSession,
    override val token: KaLifetimeToken
) : KaSessionComponent<KaFirSession>(), KaTypeProvider, KaFirSessionComponent {
    override val builtinTypes: KaBuiltinTypes by lazy {
        KaFirBuiltInTypes(rootModuleSession.builtinTypes, firSymbolBuilder, token)
    }

    override fun KaType.approximateToSuperPublicDenotable(approximateLocalTypes: Boolean): KaType? = withValidityAssertion {
        require(this is KaFirType)
        val coneType = coneType
        val approximatedConeType = PublicTypeApproximator.approximateTypeToPublicDenotable(
            coneType,
            rootModuleSession,
            approximateLocalTypes = approximateLocalTypes,
        )

        return approximatedConeType?.asKtType()
    }

    override fun KaType.approximateToSubPublicDenotable(approximateLocalTypes: Boolean): KaType? = withValidityAssertion {
        require(this is KaFirType)
        val coneType = coneType
        val approximatedConeType = rootModuleSession.typeApproximator.approximateToSubType(
            coneType,
            PublicTypeApproximator.PublicApproximatorConfiguration(approximateLocalTypes = approximateLocalTypes),
        )

        return approximatedConeType?.asKtType()
    }

    override val KaType.enhancedType: KaType?
        get() = withValidityAssertion {
            require(this is KaFirType)
            val coneType = coneType
            val substitutor = EnhancedForWarningConeSubstitutor(typeContext)
            val enhancedConeType = substitutor.substituteType(coneType)

            return enhancedConeType?.asKtType()
        }

    override val KaNamedClassOrObjectSymbol.defaultType: KaType
        get() = withValidityAssertion {
            require(this is KaFirNamedClassOrObjectSymbol)
            firSymbol.lazyResolveToPhase(FirResolvePhase.SUPER_TYPES)
            val firClass = firSymbol.fir
            val type = ConeClassLikeTypeImpl(
                firClass.symbol.toLookupTag(),
                firClass.typeParameters.map { ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), isNullable = false) }.toTypedArray(),
                isNullable = false
            )

            return type.asKtType()
        }

    override val Iterable<KaType>.commonSupertype: KaType
        get() = withValidityAssertion {
            val coneTypes = map { it.coneType }
            if (coneTypes.isEmpty()) {
                throw IllegalArgumentException("Got no types")
            }

            return analysisSession.useSiteSession.typeContext.commonSuperTypeOrNull(coneTypes)!!.asKtType()
        }

    override val KtTypeReference.type: KaType
        get() = withValidityAssertion {
            val fir = getFirBySymbols() ?: getOrBuildFirOfType<FirElement>(firResolveSession)
            return when (fir) {
                is FirResolvedTypeRef -> fir.coneType.asKtType()
                is FirDelegatedConstructorCall -> fir.constructedTypeRef.coneType.asKtType()
                is FirTypeProjectionWithVariance -> {
                    when (val typeRef = fir.typeRef) {
                        is FirResolvedTypeRef -> typeRef.coneType.asKtType()
                        else -> throwUnexpectedFirElementError(fir, this)
                    }
                }
                else -> throwUnexpectedFirElementError(fir, this)
            }
        }

    /**
     * Try to get fir element for type reference through symbols.
     * When the type is declared in compiled code this is faster than building FIR from decompiled text.
     */
    private fun KtTypeReference.getFirBySymbols(): FirElement? {
        val parent = parent
        return when {
            parent is KtParameter && parent.ownerFunction != null && parent.typeReference === this ->
                parent.resolveToFirSymbolOfTypeSafe<FirValueParameterSymbol>(firResolveSession, FirResolvePhase.TYPES)?.fir?.returnTypeRef

            parent is KtCallableDeclaration && (parent is KtNamedFunction || parent is KtProperty)
                    && (parent.receiverTypeReference === this || parent.typeReference === this) -> {
                val firCallable = parent.resolveToFirSymbolOfTypeSafe<FirCallableSymbol<*>>(
                    firResolveSession, FirResolvePhase.TYPES
                )?.fir
                if (parent.receiverTypeReference === this) {
                    firCallable?.receiverParameter?.typeRef
                } else firCallable?.returnTypeRef
            }

            parent is KtConstructorCalleeExpression && parent.parent is KtAnnotationEntry -> {
                fun getFirDeclaration(annotationEntry: KtAnnotationEntry, ktTypeReference: KtTypeReference): FirMemberDeclaration? {
                    if (annotationEntry.typeReference !== ktTypeReference) return null
                    val declaration = annotationEntry.parent?.parent as? KtNamedDeclaration ?: return null
                    return when {
                        declaration is KtClassOrObject -> declaration.resolveToFirSymbolOfTypeSafe<FirClassLikeSymbol<*>>(
                            firResolveSession, FirResolvePhase.TYPES
                        )?.fir
                        declaration is KtParameter && declaration.ownerFunction != null ->
                            declaration.resolveToFirSymbolOfTypeSafe<FirValueParameterSymbol>(
                                firResolveSession, FirResolvePhase.TYPES
                            )?.fir
                        declaration is KtCallableDeclaration && (declaration is KtNamedFunction || declaration is KtProperty) -> {
                            declaration.resolveToFirSymbolOfTypeSafe<FirCallableSymbol<*>>(
                                firResolveSession, FirResolvePhase.TYPES
                            )?.fir
                        }
                        else -> return null
                    }
                }

                fun FirMemberDeclaration.findAnnotationTypeRef(annotationEntry: KtAnnotationEntry) = annotations.find {
                    it.psi === annotationEntry
                }?.annotationTypeRef

                val annotationEntry = parent.parent as KtAnnotationEntry
                val firDeclaration = getFirDeclaration(annotationEntry, this)
                if (firDeclaration != null) {
                    firDeclaration.findAnnotationTypeRef(annotationEntry) ?: (firDeclaration as? FirProperty)?.run {
                        backingField?.findAnnotationTypeRef(annotationEntry)
                            ?: getter?.findAnnotationTypeRef(annotationEntry)
                            ?: setter?.findAnnotationTypeRef(annotationEntry)
                    }
                } else null
            }
            else -> null
        }
    }

    override val KtDoubleColonExpression.receiverType: KaType?
        get() = withValidityAssertion {
            return when (val fir = getOrBuildFir(firResolveSession)) {
                is FirGetClassCall -> {
                    fir.resolvedType.getReceiverOfReflectionType()?.asKtType()
                }
                is FirCallableReferenceAccess -> {
                    when (val explicitReceiver = fir.explicitReceiver) {
                        is FirThisReceiverExpression -> {
                            explicitReceiver.resolvedType.asKtType()
                        }
                        is FirPropertyAccessExpression -> {
                            explicitReceiver.resolvedType.asKtType()
                        }
                        is FirFunctionCall -> {
                            explicitReceiver.resolvedType.asKtType()
                        }
                        is FirResolvedQualifier -> {
                            explicitReceiver.symbol?.toLookupTag()?.constructType(
                                explicitReceiver.typeArguments.map { it.toConeTypeProjection() }.toTypedArray(),
                                isNullable = explicitReceiver.isNullableLHSForCallableReference
                            )?.asKtType()
                                ?: fir.resolvedType.getReceiverOfReflectionType()?.asKtType()
                        }
                        else -> {
                            fir.resolvedType.getReceiverOfReflectionType()?.asKtType()
                        }
                    }
                }
                else -> {
                    throwUnexpectedFirElementError(fir, this)
                }
            }
        }

    private fun ConeKotlinType.getReceiverOfReflectionType(): ConeKotlinType? {
        if (this !is ConeClassLikeType) return null
        if (lookupTag.classId.packageFqName != StandardClassIds.BASE_REFLECT_PACKAGE) return null
        return typeArguments.firstOrNull()?.type
    }

    override fun KaType.withNullability(newNullability: KaTypeNullability): KaType = withValidityAssertion {
        require(this is KaFirType)
        return coneType.withNullability(newNullability.toConeNullability(), rootModuleSession.typeContext).asKtType()
    }

    override fun KaType.hasCommonSubTypeWith(that: KaType): Boolean = withValidityAssertion {
        return analysisSession.useSiteSession.typeContext.isCompatible(
            this.coneType,
            that.coneType
        ) == ConeTypeCompatibilityChecker.Compatibility.COMPATIBLE
    }

    override fun collectImplicitReceiverTypes(position: KtElement): List<KaType> = withValidityAssertion {
        val ktFile = position.containingKtFile
        val firFile = ktFile.getOrBuildFirFile(firResolveSession)

        val fileSession = firFile.llFirSession
        val sessionHolder = SessionHolderImpl(fileSession, fileSession.getScopeSession())

        val context = ContextCollector.process(firFile, sessionHolder, position)
            ?: errorWithAttachment("Cannot find context for ${position::class}") {
                withPsiEntry("position", position)
            }

        return context.towerDataContext.implicitReceiverStack.map { it.type.asKtType() }
    }

    override fun KaType.directSuperTypes(shouldApproximate: Boolean): Sequence<KaType> = withValidityAssertion {
        require(this is KaFirType)
        return coneType.getDirectSuperTypes(shouldApproximate).map { it.asKtType() }
    }

    private fun ConeKotlinType.getDirectSuperTypes(shouldApproximate: Boolean): Sequence<ConeKotlinType> {
        return when (this) {
            // We also need to collect those on `upperBound` due to nullability.
            is ConeFlexibleType -> lowerBound.getDirectSuperTypes(shouldApproximate) + upperBound.getDirectSuperTypes(shouldApproximate)
            is ConeDefinitelyNotNullType -> original.getDirectSuperTypes(shouldApproximate).map {
                ConeDefinitelyNotNullType.create(it, analysisSession.useSiteSession.typeContext) ?: it
            }
            is ConeIntersectionType -> intersectedTypes.asSequence().flatMap { it.getDirectSuperTypes(shouldApproximate) }
            is ConeErrorType -> emptySequence()
            is ConeLookupTagBasedType -> getSubstitutedSuperTypes(shouldApproximate)
            else -> emptySequence()
        }.distinct()
    }

    private fun ConeLookupTagBasedType.getSubstitutedSuperTypes(shouldApproximate: Boolean): Sequence<ConeKotlinType> {
        val session = analysisSession.firResolveSession.useSiteFirSession
        val symbol = lookupTag.toSymbol(session)
        val superTypes = when (symbol) {
            is FirAnonymousObjectSymbol -> symbol.resolvedSuperTypes
            is FirRegularClassSymbol -> symbol.resolvedSuperTypes
            is FirTypeAliasSymbol -> symbol.fullyExpandedClass(session)?.resolvedSuperTypes ?: return emptySequence()
            is FirTypeParameterSymbol -> symbol.resolvedBounds.map { it.type }
            else -> return emptySequence()
        }

        val typeParameterSymbols = symbol.typeParameterSymbols ?: return superTypes.asSequence()
        val argumentTypes = (session.typeContext.captureArguments(this, CaptureStatus.FROM_EXPRESSION)?.toList()
            ?: this.typeArguments.mapNotNull { it.type })

        require(typeParameterSymbols.size == argumentTypes.size) {
            val renderedSymbol = FirRenderer.noAnnotationBodiesAccessorAndArguments().renderElementAsString(symbol.fir)
            "'$renderedSymbol' expects '${typeParameterSymbols.size}' type arguments " +
                    "but type '${this.renderForDebugging()}' has ${argumentTypes.size} type arguments."
        }

        val substitutor = substitutorByMap(typeParameterSymbols.zip(argumentTypes).toMap(), session)
        return superTypes.asSequence().map {
            val type = substitutor.substituteOrSelf(it)
            if (shouldApproximate) {
                session.typeApproximator.approximateToSuperType(
                    type,
                    TypeApproximatorConfiguration.FinalApproximationAfterResolutionAndInference
                ) ?: type
            } else {
                type
            }.withNullability(nullability, session.typeContext)
        }
    }

    override fun KaType.allSuperTypes(shouldApproximate: Boolean): Sequence<KaType> = withValidityAssertion {
        require(this is KaFirType)
        return listOf(this.coneType)
            .bfs { it.getDirectSuperTypes(shouldApproximate).iterator() }
            .drop(1)
            .map { it.asKtType() }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val KaCallableSymbol.dispatchReceiverType: KaType?
        get() = withValidityAssertion {
            require(this is KaFirSymbol<*>)
            val firSymbol = firSymbol
            check(firSymbol is FirCallableSymbol<*>) {
                "Fir declaration should be FirCallableDeclaration; instead it was ${firSymbol::class}"
            }
            return firSymbol.dispatchReceiverType(analysisSession.firSymbolBuilder)
        }

    override val KaType.arrayElementType: KaType?
        get() = withValidityAssertion {
            require(this is KaFirType)
            return coneType.arrayElementType()?.asKtType()
        }

}

