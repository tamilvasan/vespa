// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.processing.multifieldresolver.RankProfileTypeSettingsProcessor;
import com.yahoo.vespa.model.container.search.QueryProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Executor of processors. This defines the right order of processor execution.
 *
 * @author bratseth
 * @author bjorncs
 */
public class Processing {

    private Collection<ProcessorFactory> processors() {
        return Arrays.asList(
                SearchMustHaveDocument::new,
                UrlFieldValidator::new,
                BuiltInFieldSets::new,
                ReservedDocumentNames::new,
                IndexFieldNames::new,
                IntegerIndex2Attribute::new,
                MakeAliases::new,
                SetLanguage::new,
                UriHack::new,
                LiteralBoost::new,
                TagType::new,
                ValidateFieldTypesDocumentsOnly::new,
                IndexingInputs::new,
                OptimizeIlscript::new,
                ValidateFieldWithIndexSettingsCreatesIndex::new,
                AttributesImplicitWord::new,
                MutableAttributes::new,
                CreatePositionZCurve::new,
                WordMatch::new,
                DeprecateAttributePrefetch::new,
                ImportedFieldsResolver::new,
                ImplicitSummaries::new,
                ImplicitSummaryFields::new,
                SummaryConsistency::new,
                SummaryNamesFieldCollisions::new,
                SummaryFieldsMustHaveValidSource::new,
                AddAttributeTransformToSummaryOfImportedFields::new,
                MakeDefaultSummaryTheSuperSet::new,
                Bolding::new,
                AttributeProperties::new,
                SetRankTypeEmptyOnFilters::new,
                IndexSettingsNonFieldNames::new,
                SummaryDynamicStructsArrays::new,
                StringSettingsOnNonStringFields::new,
                IndexingOutputs::new,
                ExactMatch::new,
                NGramMatch::new,
                TextMatch::new,
                MultifieldIndexHarmonizer::new,
                FilterFieldNames::new,
                MatchConsistency::new,
                ValidateFieldTypes::new,
                DisallowComplexMapAndWsetKeyTypes::new,
                SortingSettings::new,
                FieldSetValidity::new,
                AddExtraFieldsToDocument::new,
                PredicateProcessor::new,
                MatchPhaseSettingsValidator::new,
                DiversitySettingsValidator::new,
                TensorFieldProcessor::new,
                RankProfileTypeSettingsProcessor::new,
                ReferenceFieldsProcessor::new,
                FastAccessValidator::new,
                ReservedFunctionNames::new,
                RankingExpressionTypeValidator::new,
                // These should be last:
                IndexingValidation::new,
                IndexingValues::new);
    }

    /**
     * Runs all search processors on the given {@link Search} object. These will modify the search object, <b>possibly
     * exchanging it with another</b>, as well as its document types.
     *
     * @param search The search to process.
     * @param deployLogger The log to log messages and warnings for application deployment to
     * @param rankProfileRegistry a {@link com.yahoo.searchdefinition.RankProfileRegistry}
     * @param queryProfiles The query profiles contained in the application this search is part of.
     */
    public void process(Search search, DeployLogger deployLogger, RankProfileRegistry rankProfileRegistry,
                        QueryProfiles queryProfiles, boolean validate, boolean documentsOnly) {
        Collection<ProcessorFactory> factories = processors();
        search.process();
        factories.stream()
                .map(factory -> factory.create(search, deployLogger, rankProfileRegistry, queryProfiles))
                .forEach(processor -> processor.process(validate, documentsOnly));
    }

    @FunctionalInterface
    public interface ProcessorFactory {
        Processor create(Search search, DeployLogger deployLogger, RankProfileRegistry rankProfileRegistry, QueryProfiles queryProfiles);
    }

}
