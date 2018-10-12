// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.document.*;
import com.yahoo.searchdefinition.Index;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.document.*;
import com.yahoo.searchdefinition.processing.ExactMatch;
import com.yahoo.searchdefinition.processing.NGramMatch;
import com.yahoo.vespa.documentmodel.SummaryField;
import com.yahoo.search.config.IndexInfoConfig;

import java.util.*;

/**
 * Per-index commands which should be applied to queries prior to searching
 *
 * @author bratseth
 */
public class IndexInfo extends Derived implements IndexInfoConfig.Producer {

    private static final String CMD_ATTRIBUTE = "attribute";
    private static final String CMD_DEFAULT_POSITION = "default-position";
    private static final String CMD_DYNTEASER = "dynteaser";
    private static final String CMD_FULLURL = "fullurl";
    private static final String CMD_HIGHLIGHT = "highlight";
    private static final String CMD_INDEX = "index";
    private static final String CMD_LOWERCASE = "lowercase";
    private static final String CMD_MATCH_GROUP = "match-group ";
    private static final String CMD_NORMALIZE = "normalize";
    private static final String CMD_STEM = "stem";
    private static final String CMD_URLHOST = "urlhost";
    private static final String CMD_WORD = "word";
    private static final String CMD_PLAIN_TOKENS = "plain-tokens";
    private static final String CMD_MULTIVALUE = "multivalue";
    private static final String CMD_FAST_SEARCH = "fast-search";
    private static final String CMD_PREDICATE_BOUNDS = "predicate-bounds";
    private static final String CMD_NUMERICAL = "numerical";
    private Set<IndexCommand> commands = new java.util.LinkedHashSet<>();
    private Map<String, String> aliases = new java.util.LinkedHashMap<>();
    private Map<String, FieldSet> fieldSets;
    private Search search;

    public IndexInfo(Search search) {
        this.fieldSets = search.fieldSets().userFieldSets();
        addIndexCommand("sddocname", CMD_INDEX);
        addIndexCommand("sddocname", CMD_WORD);
        derive(search);
    }

    @Override
    protected void derive(Search search) {
        super.derive(search); // Derive per field
        this.search = search;
        // Populate fieldsets with actual field objects, bit late to do that here but
        for (FieldSet fs : fieldSets.values()) {
            for (String fieldName : fs.getFieldNames()) {
                fs.fields().add(search.getField(fieldName));
            }
        }
        // Must follow, because index settings overrides field settings
        for (Index index : search.getExplicitIndices()) {
            derive(index, search);
        }

        // Commands for summary fields
        // TODO: Move to fieldinfo and implement differently. This is not right
        for (SummaryField summaryField : search.getUniqueNamedSummaryFields().values()) {
            if (summaryField.getTransform().isTeaser()) {
                addIndexCommand(summaryField.getName(), CMD_DYNTEASER);
            }
            if (summaryField.getTransform().isBolded()) {
                addIndexCommand(summaryField.getName(), CMD_HIGHLIGHT);
            }
        }
    }

    @Override
    protected void derive(Index index, Search search) {
        if (index.getMatchGroup().size() > 0) {
            addIndexCommand(index.getName(), CMD_MATCH_GROUP + toSpaceSeparated(index.getMatchGroup()));
        }
    }

    private String toSpaceSeparated(Collection c) {
        StringBuffer b = new StringBuffer();
        for (Iterator i = c.iterator(); i.hasNext();) {
            b.append(i.next());
            if (i.hasNext()) {
                b.append(" ");
            }
        }
        return b.toString();
    }

    @Override
    protected void derive(ImmutableSDField field, Search search) {
        derive(field, search, false);
    }

    protected void derive(ImmutableSDField field, Search search, boolean inPosition) {
        if (field.getDataType().equals(DataType.PREDICATE)) {
            Index index = field.getIndex(field.getName());
            if (index != null) {
                BooleanIndexDefinition options = index.getBooleanIndexDefiniton();
                if (options.hasLowerBound() || options.hasUpperBound()) {
                    addIndexCommand(field.getName(), CMD_PREDICATE_BOUNDS + " [" +
                            (options.hasLowerBound() ? Long.toString(options.getLowerBound()) : "") + ".." +
                            (options.hasUpperBound() ? Long.toString(options.getUpperBound()) : "") + "]");
                }
            }
        }

        // Field level aliases
        for (Map.Entry<String, String> e : field.getAliasToName().entrySet()) {
            String alias = e.getKey();
            String name = e.getValue();
            addIndexAlias(alias, name);
        }
        boolean isPosition = field.getDataType().equals(PositionDataType.INSTANCE) ||
                field.getDataType().equals(DataType.getArray(PositionDataType.INSTANCE));
        if (field.usesStructOrMap()) {
            for (ImmutableSDField structField : field.getStructFields()) {
                derive(structField, search, isPosition); // Recursion
            }
        }

        if (isPosition) {
            addIndexCommand(field.getName(), CMD_DEFAULT_POSITION);
        }

        addIndexCommand(field, CMD_INDEX); // List the indices

        if (field.doesIndexing() || field.doesLowerCasing()) {
            addIndexCommand(field, CMD_LOWERCASE);
        }

        if (field.getDataType().isMultivalue()) {
            addIndexCommand(field, CMD_MULTIVALUE);
        }

        Attribute attribute = field.getAttributes().get(field.getName());
        if ((field.doesAttributing() || (attribute != null && !inPosition)) && !field.doesIndexing()) {
            addIndexCommand(field.getName(), CMD_ATTRIBUTE);
            if (attribute != null && attribute.isFastSearch())
                addIndexCommand(field.getName(), CMD_FAST_SEARCH);
        } else if (field.doesIndexing()) {
            if (stemSomehow(field, search)) {
                addIndexCommand(field, stemCmd(field, search), new StemmingOverrider(this, search));
            }
            if (normalizeAccents(field)) {
                addIndexCommand(field, CMD_NORMALIZE);
            }
            if (field.getMatching() == null || field.getMatching().getType().equals(Matching.Type.TEXT)) {
                addIndexCommand(field, CMD_PLAIN_TOKENS);
            }
        }

        if (isUriField(field)) {
            addUriIndexCommands(field);
        }

        if (field.getDataType() instanceof NumericDataType) {
            addIndexCommand(field, CMD_NUMERICAL);
        }

        // Explicit commands
        for (String command : field.getQueryCommands()) {
            addIndexCommand(field, command);
        }

    }

    static String stemCmd(ImmutableSDField field, Search search) {
        return CMD_STEM + ":" + field.getStemming(search).toStemMode();
    }

    private boolean stemSomehow(ImmutableSDField field, Search search) {
        if (field.getStemming(search).equals(Stemming.NONE)) return false;
        return isTypeOrNested(field, DataType.STRING);
    }

    private boolean normalizeAccents(ImmutableSDField field) {
        return field.getNormalizing().doRemoveAccents() && isTypeOrNested(field, DataType.STRING);
    }

    private boolean isTypeOrNested(ImmutableSDField field, DataType type) {
        return field.getDataType().equals(type) || field.getDataType().equals(DataType.getArray(type)) ||
               field.getDataType().equals(DataType.getWeightedSet(type));
    }

    private boolean isUriField(ImmutableSDField field) {
        DataType fieldType = field.getDataType();
        if (DataType.URI.equals(fieldType)) {
            return true;
        }
        if (fieldType instanceof CollectionDataType &&
            DataType.URI.equals(((CollectionDataType)fieldType).getNestedType()))
        {
            return true;
        }
        return false;
    }

    private void addUriIndexCommands(ImmutableSDField field) {
        String fieldName = field.getName();
        addIndexCommand(fieldName, CMD_FULLURL);
        addIndexCommand(fieldName, CMD_LOWERCASE);
        addIndexCommand(fieldName + "." + fieldName, CMD_FULLURL);
        addIndexCommand(fieldName + "." + fieldName, CMD_LOWERCASE);
        addIndexCommand(fieldName + ".path", CMD_FULLURL);
        addIndexCommand(fieldName + ".path", CMD_LOWERCASE);
        addIndexCommand(fieldName + ".query", CMD_FULLURL);
        addIndexCommand(fieldName + ".query", CMD_LOWERCASE);
        addIndexCommand(fieldName + ".hostname", CMD_URLHOST);
        addIndexCommand(fieldName + ".hostname", CMD_LOWERCASE);

        // XXX hack
        Index index = field.getIndex("hostname");
        if (index != null) {
            addIndexCommand(index, CMD_URLHOST);
        }
    }

    /**
     * Sets a command for all indices of a field
     */
    private void addIndexCommand(Index index, String command) {
        addIndexCommand(index.getName(), command);
    }

    /**
     * Sets a command for all indices of a field
     */
    private void addIndexCommand(ImmutableSDField field, String command) {
        addIndexCommand(field, command, null);
    }

    /**
     * Sets a command for all indices of a field
     */
    private void addIndexCommand(ImmutableSDField field, String command, IndexOverrider overrider) {
        if (overrider == null || !overrider.override(field.getName(), command, field)) {
            addIndexCommand(field.getName(), command);
        }
    }

    private void addIndexCommand(String indexName, String command) {
        commands.add(new IndexCommand(indexName, command));
    }

    private void addIndexAlias(String alias, String indexName) {
        aliases.put(alias, indexName);
    }

    /**
     * Returns whether a particular command is prsent in this index info
     */
    public boolean hasCommand(String indexName, String command) {
        return commands.contains(new IndexCommand(indexName, command));
    }

    private boolean notInCommands(String index) {
        for (IndexCommand command : commands) {
            if (command.getIndex().equals(index)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void getConfig(IndexInfoConfig.Builder builder) {
        IndexInfoConfig.Indexinfo.Builder iiB = new IndexInfoConfig.Indexinfo.Builder();
        iiB.name(getName());
        for (IndexCommand command : commands) {
            iiB.command(
                    new IndexInfoConfig.Indexinfo.Command.Builder()
                        .indexname(command.getIndex())
                        .command(command.getCommand()));
        }
        // Make user defined field sets searchable
        for (FieldSet fieldSet : fieldSets.values()) {
        	 if (notInCommands(fieldSet.getName())) {
        		 addFieldSetCommands(iiB, fieldSet);
        	 }
        }

        for (Map.Entry<String, String> e : aliases.entrySet()) {
            iiB.alias(
                    new IndexInfoConfig.Indexinfo.Alias.Builder()
                        .alias(e.getKey())
                        .indexname(e.getValue()));
        }
        builder.indexinfo(iiB);
    }

    // TODO: Move this to the FieldSetValidity processor (and rename it) as that already has to look at this.
    private void addFieldSetCommands(IndexInfoConfig.Indexinfo.Builder iiB, FieldSet fieldSet) {
        // Explicit query commands on the field set, overrides everything.
        if (!fieldSet.queryCommands().isEmpty()) {
            for (String qc : fieldSet.queryCommands()) {
                iiB.command(
                        new IndexInfoConfig.Indexinfo.Command.Builder()
                        .indexname(fieldSet.getName())
                        .command(qc));
            }
            return;
        }        
        boolean anyIndexing = false;
        boolean anyAttributing = false;
        boolean anyLowerCasing = false;
        boolean anyStemming = false;
        boolean anyNormalizing = false;
        String stemmingCommand = null;
        Matching fieldSetMatching = fieldSet.getMatching(); // null if no explicit matching
        // First a pass over the fields to read some params to decide field settings implicitly:
        for (ImmutableSDField field : fieldSet.fields()) {
            if (field.doesIndexing()) {
                anyIndexing = true;
            }
            if (field.doesAttributing()) {
                anyAttributing = true;
            }
            if (field.doesIndexing() || field.doesLowerCasing()) {
                anyLowerCasing = true;
            }
            if (stemming(field)) {
                anyStemming = true;
                stemmingCommand = CMD_STEM + ":" + getEffectiveStemming(field).toStemMode();
            }
            if (field.getNormalizing().doRemoveAccents()) {
                anyNormalizing = true;
            }
            if (fieldSetMatching == null && field.getMatching().getType() != Matching.defaultType)
                fieldSetMatching = field.getMatching();
        }
        if (anyIndexing && anyAttributing && fieldSet.getMatching() == null) {
            // We have both attributes and indexes and no explicit match setting ->
            // use default matching as that at least works if the data in the attribute consists
            // of single tokens only.
            fieldSetMatching = new Matching();
        }
        if (anyLowerCasing) {
            iiB.command(
                    new IndexInfoConfig.Indexinfo.Command.Builder()
                        .indexname(fieldSet.getName())
                        .command(CMD_LOWERCASE));
        }
        if (hasMultiValueField(fieldSet)) {
            iiB.command(
                    new IndexInfoConfig.Indexinfo.Command.Builder()
                            .indexname(fieldSet.getName())
                            .command(CMD_MULTIVALUE));
        }
        if (anyIndexing) {
            iiB.command(
                    new IndexInfoConfig.Indexinfo.Command.Builder()
                        .indexname(fieldSet.getName())
                        .command(CMD_INDEX));
            if ( ! isExactMatch(fieldSetMatching)) {
                if (fieldSetMatching == null || fieldSetMatching.getType().equals(Matching.Type.TEXT)) {
                    iiB.command(
                            new IndexInfoConfig.Indexinfo.Command.Builder()
                                    .indexname(fieldSet.getName())
                                    .command(CMD_PLAIN_TOKENS));
                }
                if (anyStemming) {
                    iiB.command(
                        new IndexInfoConfig.Indexinfo.Command.Builder()
                            .indexname(fieldSet.getName())
                            .command(stemmingCommand));
                }
                if (anyNormalizing)
                    iiB.command(
                            new IndexInfoConfig.Indexinfo.Command.Builder()
                                .indexname(fieldSet.getName())
                                .command(CMD_NORMALIZE));
            }
        } else {
            // Assume only attribute fields
            iiB
            .command(
                new IndexInfoConfig.Indexinfo.Command.Builder()
                    .indexname(fieldSet.getName())
                    .command(CMD_ATTRIBUTE))
            .command(
                new IndexInfoConfig.Indexinfo.Command.Builder()
                    .indexname(fieldSet.getName())
                    .command(CMD_INDEX));
        }
        if (fieldSetMatching != null) {
            // Explicit matching set on fieldset
            if (fieldSetMatching.getType().equals(Matching.Type.EXACT)) {
                String term = fieldSetMatching.getExactMatchTerminator();
                if (term==null) term=ExactMatch.DEFAULT_EXACT_TERMINATOR;
                iiB.command(
                        new IndexInfoConfig.Indexinfo.Command.Builder()
                            .indexname(fieldSet.getName())
                            .command("exact "+term));
            } else if (fieldSetMatching.getType().equals(Matching.Type.WORD)) {
                iiB.command(
                        new IndexInfoConfig.Indexinfo.Command.Builder()
                            .indexname(fieldSet.getName())
                            .command(CMD_WORD));
            } else if (fieldSetMatching.getType().equals(Matching.Type.GRAM)) {
                iiB.command(
                        new IndexInfoConfig.Indexinfo.Command.Builder()
                            .indexname(fieldSet.getName())
                            .command("ngram "+(fieldSetMatching.getGramSize()>0 ? fieldSetMatching.getGramSize() : NGramMatch.DEFAULT_GRAM_SIZE)));
            } else if (fieldSetMatching.getType().equals(Matching.Type.TEXT)) {
                
            }
            
        }
       
    }

    private boolean hasMultiValueField(FieldSet fieldSet) {
        for (ImmutableSDField field : fieldSet.fields()) {
            if (field.getDataType().isMultivalue())
                return true;
        }
        return false;
    }

    private Stemming getEffectiveStemming(ImmutableSDField field) {
        Stemming active = field.getStemming(search);
        if (field.getIndex(field.getName()) != null) {
            if (field.getIndex(field.getName()).getStemming()!=null) {
                active = field.getIndex(field.getName()).getStemming();
            }
        }
        if (active != null) {
            return active;
        }
        // assume default: TODO: Change to Stemming.BEST on Vespa 7
        return Stemming.SHORTEST;
    }

    private boolean stemming(ImmutableSDField field) {
        if (field.getStemming() != null) {
            return !field.getStemming().equals(Stemming.NONE);
        }
        if (search.getStemming()==Stemming.NONE) return false;
        if (field.isImportedField()) return false;
        if (field.getIndex(field.getName())==null) return true;
        if (field.getIndex(field.getName()).getStemming()==null) return true;
        return !(field.getIndex(field.getName()).getStemming().equals(Stemming.NONE));
    }

    private boolean isExactMatch(Matching m) {
        if (m == null) return false;
        if (m.getType().equals(Matching.Type.EXACT)) return true;
        if (m.getType().equals(Matching.Type.WORD)) return true;
        return false;
    }

    @Override
    protected String getDerivedName() {
        return "index-info";
    }

    /**
     * An index command. Null commands are also represented, to detect consistency issues. This is an (immutable) value
     * object.
     */
    public static class IndexCommand {

        private String index;

        private String command;

        public IndexCommand(String index, String command) {
            this.index = index;
            this.command = command;
        }

        public String getIndex() {
            return index;
        }

        public String getCommand() {
            return command;
        }

        /**
         * Returns true if this is the null command (do nothing)
         */
        public boolean isNull() {
            return command.equals("");
        }

        public int hashCode() {
            return index.hashCode() + 17 * command.hashCode();
        }

        public boolean equals(Object object) {
            if (!(object instanceof IndexCommand)) {
                return false;
            }

            IndexCommand other = (IndexCommand)object;
            return
                    other.index.equals(this.index) &&
                    other.command.equals(this.command);
        }

        public String toString() {
            return "index command " + command + " on index " + index;
        }

    }

    /**
     * A command which may override the command setting of a field for a particular index
     */
    private static abstract class IndexOverrider {

        protected IndexInfo owner;

        public IndexOverrider(IndexInfo owner) {
            this.owner = owner;
        }

        /**
         * Override the setting of this index for this field, returns true if overriden, false if this index should be
         * set according to the field
         */
        public abstract boolean override(String indexName, String command, ImmutableSDField field);

    }

    private static class StemmingOverrider extends IndexOverrider {

        private Search search;

        public StemmingOverrider(IndexInfo owner, Search search) {
            super(owner);
            this.search = search;
        }

        public boolean override(String indexName, String command, ImmutableSDField field) {
            if (search == null) {
                return false;
            }

            Index index = search.getIndex(indexName);
            if (index == null) {
                return false;
            }

            Stemming indexStemming = index.getStemming();
            if (indexStemming == null) {
                return false;
            }

            if (Stemming.NONE.equals(indexStemming)) {
                // Add nothing
            } else {
                owner.addIndexCommand(indexName, CMD_STEM + ":" + indexStemming.toStemMode());
            }
            return true;
        }

    }

}
