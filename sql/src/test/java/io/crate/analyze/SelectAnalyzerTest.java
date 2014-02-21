/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import com.google.common.collect.ImmutableList;
import io.crate.metadata.MetaDataModule;
import io.crate.metadata.ReferenceInfo;
import io.crate.metadata.doc.DocSchemaInfo;
import io.crate.metadata.sys.MetaDataSysModule;
import io.crate.metadata.sys.SysNodesTableInfo;
import io.crate.metadata.table.SchemaInfo;
import io.crate.operator.aggregation.impl.AggregationImplModule;
import io.crate.operator.aggregation.impl.AverageAggregation;
import io.crate.operator.aggregation.impl.CollectSetAggregation;
import io.crate.operator.operator.*;
import io.crate.operator.predicate.IsNullPredicate;
import io.crate.operator.predicate.NotPredicate;
import io.crate.operator.predicate.PredicateModule;
import io.crate.operator.reference.sys.node.NodeLoadExpression;
import io.crate.operator.scalar.CollectionCountFunction;
import io.crate.operator.scalar.ScalarFunctionModule;
import io.crate.planner.RowGranularity;
import io.crate.planner.symbol.*;
import org.apache.lucene.util.BytesRef;
import org.cratedb.DataType;
import org.cratedb.sql.AmbiguousAliasException;
import org.elasticsearch.common.inject.Module;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Test;

import java.util.*;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class SelectAnalyzerTest extends BaseAnalyzerTest {

    static class TestMetaDataModule extends MetaDataModule {

        @Override
        protected void bindReferences() {
            super.bindReferences();
            referenceBinder.addBinding(LOAD_INFO.ident()).to(NodeLoadExpression.class).asEagerSingleton();
            referenceBinder.addBinding(CLUSTER_NAME_INFO.ident()).toInstance(new ClusterNameExpression());
        }

        @Override
        protected void bindSchemas() {
            super.bindSchemas();
            SchemaInfo schemaInfo = mock(SchemaInfo.class);
            when(schemaInfo.getTableInfo(TEST_DOC_TABLE_IDENT.name())).thenReturn(userTableInfo);
            schemaBinder.addBinding(DocSchemaInfo.NAME).toInstance(schemaInfo);
        }
    }

    @Override
    protected List<Module> getModules() {
        List<Module> modules = super.getModules();
        modules.addAll(Arrays.<Module>asList(
                new TestModule(),
                new TestMetaDataModule(),
                new MetaDataSysModule(),
                new OperatorModule(),
                new AggregationImplModule(),
                new PredicateModule(),
                new ScalarFunctionModule()
        ));
        return modules;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGroupedSelectMissingOutput() throws Exception {
        analyze("select load['5'] from sys.nodes group by load['1']");
    }

    @Test
    public void testIsNullQuery() {
        Analysis analysis = analyze("select * from sys.nodes where id is not null");
        assertTrue(analysis.whereClause().hasQuery());
        Function query = analysis.whereClause().query();

        assertThat(query.info().ident().name(), is(NotPredicate.NAME));
        assertThat(query.arguments().get(0), instanceOf(Function.class));
        Function isNull = (Function) query.arguments().get(0);
        assertThat(isNull.info().ident().name(), is(IsNullPredicate.NAME));
    }

    @Test
    public void testOrderedSelect() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select load['1'] from sys.nodes order by load['5'] desc");
        assertEquals(analysis.table().ident(), SysNodesTableInfo.IDENT);
        assertNull(analysis.limit());

        assertFalse(analysis.hasGroupBy());
        assertTrue(analysis.isSorted());
        assertThat(analysis.rowGranularity(), is(RowGranularity.NODE));

        assertEquals(1, analysis.outputSymbols().size());
        assertEquals(1, analysis.sortSymbols().size());
        assertEquals(1, analysis.reverseFlags().length);

        assertEquals(LOAD5_INFO, ((Reference) analysis.sortSymbols().get(0)).info());
    }

    @Test
    public void testGroupKeyNotInResultColumnList() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select count(*) from sys.nodes group by name");

        assertThat(analysis.groupBy().size(), is(1));
        assertThat(analysis.outputNames().get(0), is("count(*)"));
    }

    @Test
    public void testGroupByOnAlias() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select count(*), name as n from sys.nodes group by n");
        assertThat(analysis.groupBy().size(), is(1));
        assertThat(analysis.outputNames().get(0), is("count(*)"));
        assertThat(analysis.outputNames().get(1), is("n"));

        assertEquals(analysis.groupBy().get(0), analysis.outputSymbols().get(1));
    }

    @Test
    public void testGroupByOnOrdinal() throws Exception {
        // just like in postgres access by ordinal starts with 1
        SelectAnalysis analysis = (SelectAnalysis) analyze("select count(*), name as n from sys.nodes group by 2");
        assertThat(analysis.groupBy().size(), is(1));
        assertEquals(analysis.groupBy().get(0), analysis.outputSymbols().get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGroupByOnInvalidOrdinal() throws Exception {
        analyze("select count(*), name from sys.nodes group by -4");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGroupByOnOrdinalAggregation() throws Exception {
        analyze("select count(*), name as n from sys.nodes group by 1");
    }

    @Test
    public void testNegativeLiteral() throws Exception {
        Analysis analyze = analyze("select * from sys.nodes where port['http'] = -400");
        Function whereClause = analyze.whereClause().query();
        Symbol symbol = whereClause.arguments().get(1);
        assertThat(((IntegerLiteral) symbol).value(), is(-400));
    }

    @Test
    public void testGroupedSelect() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select load['1'], count(*) from sys.nodes group by load['1']");
        assertEquals(analysis.table().ident(), SysNodesTableInfo.IDENT);
        assertNull(analysis.limit());

        assertThat(analysis.rowGranularity(), is(RowGranularity.NODE));
        assertTrue(analysis.hasGroupBy());
        assertEquals(2, analysis.outputSymbols().size());
        assertEquals(1, analysis.groupBy().size());
        assertEquals(LOAD1_INFO, ((Reference) analysis.groupBy().get(0)).info());

    }


    @Test
    public void testSimpleSelect() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select load['5'] from sys.nodes limit 2");
        assertEquals(analysis.table().ident(), SysNodesTableInfo.IDENT);
        assertEquals(new Integer(2), analysis.limit());

        assertFalse(analysis.hasGroupBy());

        assertThat(analysis.rowGranularity(), is(RowGranularity.NODE));

        assertEquals(SysNodesTableInfo.IDENT, analysis.table().ident());
        assertEquals(1, analysis.outputSymbols().size());
        Reference col1 = (Reference) analysis.outputSymbols().get(0);
        assertEquals(LOAD5_INFO, col1.info());

    }

    @Test
    public void testAggregationSelect() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select avg(load['5']) from sys.nodes");
        assertEquals(SysNodesTableInfo.IDENT, analysis.table().ident());

        assertThat(analysis.rowGranularity(), is(RowGranularity.NODE));

        assertFalse(analysis.hasGroupBy());
        assertEquals(1, analysis.outputSymbols().size());
        Function col1 = (Function) analysis.outputSymbols().get(0);
        assertTrue(col1.info().isAggregate());
        assertEquals(AverageAggregation.NAME, col1.info().ident().name());
    }

    @Test
    public void testAllColumnCluster() throws Exception {
        Analysis analyze = analyze("select * from sys.cluster");
        assertThat(analyze.outputNames().size(), is(2));
        assertThat(analyze.outputNames().get(0), is("id"));
        assertThat(analyze.outputNames().get(1), is("name"));

        assertThat(analyze.outputSymbols().size(), is(2));
    }

    @Test
    public void testAllColumnNodes() throws Exception {
        Analysis analyze = analyze("select id, * from sys.nodes");
        assertThat(analyze.outputNames().get(0), is("id"));
        assertThat(analyze.outputNames().get(1), is("id"));
        assertThat(analyze.outputNames().size(), is(8));
        assertEquals(analyze.outputNames().size(), analyze.outputSymbols().size());
    }

    @Test
    public void testWhereSelect() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select load from sys.nodes " +
                "where load['1'] = 1.2 or 1 >= load['5']");
        assertEquals(SysNodesTableInfo.IDENT, analysis.table().ident());

        assertThat(analysis.rowGranularity(), is(RowGranularity.NODE));

        assertFalse(analysis.hasGroupBy());

        Function whereClause = analysis.whereClause().query();
        assertEquals(OrOperator.NAME, whereClause.info().ident().name());
        assertFalse(whereClause.info().isAggregate());

        Function left = (Function) whereClause.arguments().get(0);
        assertEquals(EqOperator.NAME, left.info().ident().name());
        assertThat(left.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(left.arguments().get(1), IsInstanceOf.instanceOf(DoubleLiteral.class));

        Function right = (Function) whereClause.arguments().get(1);
        assertEquals(LteOperator.NAME, right.info().ident().name());
        assertThat(right.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(right.arguments().get(1), IsInstanceOf.instanceOf(DoubleLiteral.class));
    }

    @Test
    public void testSelectWithParameters() throws Exception {
        Analysis analysis = analyze("select load from sys.nodes " +
                "where load['1'] = ? or load['5'] <= ? or load['15'] >= ? or load['1'] = ? " +
                "or load['1'] = ? or name = ?", new Object[]{
                1.2d,
                2.4f,
                2L,
                3,
                new Short("1"),
                "node 1"
        });
        Function whereClause = analysis.whereClause().query();
        assertEquals(OrOperator.NAME, whereClause.info().ident().name());
        assertFalse(whereClause.info().isAggregate());

        Function function = (Function) whereClause.arguments().get(0);
        assertEquals(OrOperator.NAME, function.info().ident().name());
        function = (Function) function.arguments().get(1);
        assertEquals(EqOperator.NAME, function.info().ident().name());
        assertThat(function.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(function.arguments().get(1), IsInstanceOf.instanceOf(DoubleLiteral.class));

        function = (Function) whereClause.arguments().get(1);
        assertEquals(EqOperator.NAME, function.info().ident().name());
        assertThat(function.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(function.arguments().get(1), IsInstanceOf.instanceOf(StringLiteral.class));
    }

    @Test
    public void testOutputNames() throws Exception {
        Analysis analyze = analyze("select load as l, id, load['1'] from sys.nodes");
        assertThat(analyze.outputNames().size(), is(3));
        assertThat(analyze.outputNames().get(0), is("l"));
        assertThat(analyze.outputNames().get(1), is("id"));
        assertThat(analyze.outputNames().get(2), is("load['1']"));
    }

    @Test
    public void testDuplicateOutputNames() throws Exception {
        Analysis analyze = analyze("select load as l, load['1'] as l from sys.nodes");
        assertThat(analyze.outputNames().size(), is(2));
        assertThat(analyze.outputNames().get(0), is("l"));
        assertThat(analyze.outputNames().get(1), is("l"));
    }

    @Test
    public void testOrderByOnAlias() throws Exception {
        SelectAnalysis analyze = (SelectAnalysis) analyze("select load as l from sys.nodes order by l");
        assertThat(analyze.outputNames().size(), is(1));
        assertThat(analyze.outputNames().get(0), is("l"));

        assertTrue(analyze.isSorted());
        assertThat(analyze.sortSymbols().size(), is(1));
        assertThat(analyze.sortSymbols().get(0), is(analyze.outputSymbols().get(0)));
    }

    @Test(expected = AmbiguousAliasException.class)
    public void testAmbiguousOrderByOnAlias() throws Exception {
        analyze("select id as load, load from sys.nodes order by load");
    }

    @Test
    public void testOffsetSupportInAnalyzer() throws Exception {
        SelectAnalysis analyze = (SelectAnalysis) analyze("select * from sys.nodes limit 1 offset 3");
        assertThat(analyze.offset(), is(3));
    }

    @Test
    public void testNoMatchStatement() throws Exception {
        for (String stmt : ImmutableList.of(
                "select id from sys.nodes where false",
                "select id from sys.nodes where 1=0",
                "select id from sys.nodes where sys.cluster.name = 'something'"
        )) {
            Analysis analysis = analyze(stmt);
            assertTrue(stmt, analysis.noMatch());
            assertFalse(stmt, analysis.whereClause().hasQuery());
        }
    }

    @Test
    public void testEvaluatingMatchAllStatement() throws Exception {
        Analysis analysis = analyze("select id from sys.nodes where sys.cluster.name = 'testcluster'");
        assertFalse(analysis.noMatch());
        assertFalse(analysis.whereClause().hasQuery());
    }

    @Test
    public void testAllMatchStatement() throws Exception {
        for (String stmt : ImmutableList.of(
                "select id from sys.nodes where true",
                "select id from sys.nodes where 1=1",
                "select id from sys.nodes"
        )) {
            System.out.println(stmt);
            Analysis analysis = analyze(stmt);
            assertFalse(stmt, analysis.noMatch());
            assertFalse(stmt, analysis.whereClause().hasQuery());
        }
    }

    @Test
    public void testRewriteNotEquals() {
        // should rewrite to:
        //    not(eq(sys.noes.name, 'something'))
        ImmutableList<String> statements = ImmutableList.of(
                "select * from sys.nodes where sys.nodes.name <> 'something'",
                "select * from sys.nodes where sys.nodes.name != 'something'"
        );
        for (String statement : statements) {
            Analysis analysis = analyze(statement);
            WhereClause whereClause = analysis.whereClause();

            Function notFunction = whereClause.query();
            assertThat(notFunction.info().ident().name(), is(NotPredicate.NAME));
            assertThat(notFunction.arguments().size(), is(1));

            Function eqFunction = (Function) notFunction.arguments().get(0);
            assertThat(eqFunction.info().ident().name(), is(EqOperator.NAME));
            assertThat(eqFunction.arguments().size(), is(2));

            List<Symbol> eqArguments = eqFunction.arguments();
            assertThat(eqArguments.get(0), instanceOf(Reference.class));
            assertThat(eqArguments.get(1), instanceOf(StringLiteral.class));
        }
    }

    @Test
    public void testClusteredBy() throws Exception {
        Analysis analysis = analyze("select name from users where id=1");
        assertEquals(1L, analysis.clusteredByLiteral().value());

        analysis = analyze("select name from users where id=1 or id=2");
        assertNull(analysis.clusteredByLiteral());
    }

    @Test
    public void testPrimaryKeyAndVersion() throws Exception {
        SelectAnalysis analysis = (SelectAnalysis)analyze(
            "select name from users where id = 2 and \"_version\" = 1");
        assertEquals(analysis.primaryKeyLiterals(), ImmutableList.<Literal>of(new LongLiteral(2)));
        assertThat(analysis.version().get(), is(1L));
    }

    @Test
    public void test1ColPrimaryKeyLiteral() throws Exception {
        Analysis analysis = analyze("select name from sys.nodes where id='jalla'");
        assertEquals(analysis.primaryKeyLiterals(), ImmutableList.<Literal>of(new StringLiteral("jalla")));

        analysis = analyze("select name from sys.nodes where 'jalla'=id");
        assertEquals(analysis.primaryKeyLiterals(), ImmutableList.<Literal>of(new StringLiteral("jalla")));


        analysis = analyze("select name from sys.nodes where id='jalla' and id='jalla'");
        assertEquals(analysis.primaryKeyLiterals(), ImmutableList.<Literal>of(new StringLiteral("jalla")));

        analysis = analyze("select name from sys.nodes where id='jalla' and (id='jalla' or 1=1)");
        assertEquals(analysis.primaryKeyLiterals(), ImmutableList.<Literal>of(new StringLiteral("jalla")));

        // a no match results in undefined key literals, since those are ambiguous
        analysis = analyze("select name from sys.nodes where id='jalla' and id='kelle'");
        assertNull(analysis.primaryKeyLiterals());
        assertTrue(analysis.noMatch());

        analysis = analyze("select name from sys.nodes where id='jalla' or name = 'something'");
        assertNull(analysis.primaryKeyLiterals());
        assertFalse(analysis.noMatch());

        analysis = analyze("select name from sys.nodes where name = 'something'");
        assertNull(analysis.primaryKeyLiterals());
        assertFalse(analysis.noMatch());

    }

    @Test
    public void test2ColPrimaryKeyLiteral() throws Exception {
        Analysis analysis = analyze("select id from sys.shards where id=1 and table_name='jalla'");
        assertEquals(ImmutableList.<Literal>of(new StringLiteral("jalla"), new IntegerLiteral(1)),
                analysis.primaryKeyLiterals());
        assertFalse(analysis.noMatch());

        analysis = analyze("select id from sys.shards where id=1 and table_name='jalla' and id=1");
        assertEquals(ImmutableList.<Literal>of(new StringLiteral("jalla"), new IntegerLiteral(1)),
                analysis.primaryKeyLiterals());
        assertFalse(analysis.noMatch());


        analysis = analyze("select id from sys.shards where id=1");
        assertNull(analysis.primaryKeyLiterals());
        assertFalse(analysis.noMatch());

        analysis = analyze("select id from sys.shards where id=1 and table_name='jalla' and id=2");
        assertTrue(analysis.noMatch());
        assertNull(analysis.primaryKeyLiterals());
    }

    @Test
    public void test1ColPrimaryKeySetLiteralDiffMatches() throws Exception {
        Analysis analysis = analyze(
                "select name from sys.nodes where id in ('jalla', 'kelle') and id in ('jalla', 'something')");
        assertFalse(analysis.noMatch());
        assertEquals(1, analysis.primaryKeyLiterals().size());
        SetLiteral sl = (SetLiteral) analysis.primaryKeyLiterals().get(0);

        assertEquals(1, sl.value().size());
        assertEquals(SetLiteralTest.stringSet("jalla"), sl);
    }


    @Test
    public void test1ColPrimaryKeySetLiteral() throws Exception {
        Analysis analysis = analyze("select name from sys.nodes where id in ('jalla', 'kelle')");
        assertFalse(analysis.noMatch());
        assertEquals(1, analysis.primaryKeyLiterals().size());
        SetLiteral sl = (SetLiteral) analysis.primaryKeyLiterals().get(0);

        assertEquals(SetLiteralTest.stringSet("jalla", "kelle"), sl);

    }

    @Test
    public void test2ColPrimaryKeySetLiteral() throws Exception {
        Analysis analysis = analyze("select id from sys.shards where id=1 and table_name in ('jalla', 'kelle')");
        assertEquals(2, analysis.primaryKeyLiterals().size());
        SetLiteral tableName = (SetLiteral) analysis.primaryKeyLiterals().get(0);
        IntegerLiteral id = (IntegerLiteral) analysis.primaryKeyLiterals().get(1);

        assertThat(1, is(id.value()));
        assertEquals(SetLiteralTest.stringSet("jalla", "kelle"), tableName);
    }


    @Test
    public void testGranularityWithSingleAggregation() throws Exception {
        Analysis analyze = analyze("select count(*) from sys.nodes");
        assertThat(analyze.rowGranularity(), is(RowGranularity.NODE));
    }

    @Test
    public void testRewriteCountStringLiteral() {
        Analysis analysis = analyze("select count('id') from sys.nodes");
        List<Symbol> outputSymbols = analysis.outputSymbols;
        assertThat(outputSymbols.size(), is(1));
        assertThat(outputSymbols.get(0), instanceOf(Function.class));
        assertThat(((Function)outputSymbols.get(0)).arguments().size(), is(0));
    }

    @Test
    public void testRewriteCountNull() {
        Analysis analysis = analyze("select count(null) from sys.nodes");
        List<Symbol> outputSymbols = analysis.outputSymbols;
        assertThat(outputSymbols.size(), is(1));
        assertThat(outputSymbols.get(0), instanceOf(LongLiteral.class));
        assertThat(((LongLiteral)outputSymbols.get(0)).value(), is(0L));
    }

    @Test
    public void testWhereInSelect() throws Exception {
        Analysis analysis = analyze("select load from sys.nodes where load['1'] in (1, 2, 4, 8, 16)");

        Function whereClause = analysis.whereClause().query();
        assertEquals(InOperator.NAME, whereClause.info().ident().name());
        assertThat(whereClause.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(whereClause.arguments().get(1), IsInstanceOf.instanceOf(SetLiteral.class));
        SetLiteral setLiteral = (SetLiteral) whereClause.arguments().get(1);
        assertEquals(setLiteral.symbolType(), SymbolType.SET_LITERAL);
        assertEquals(setLiteral.valueType(), DataType.LONG_SET);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWhereInSelectDifferentDataTypeList() throws Exception {
        analyze("select 'found' where 1 in (1.2, 2)");
    }

    @Test
    public void testWhereInSelectDifferentDataTypeValue() throws Exception {
        Analysis analysis = analyze("select 'found' where 1.2 in (1, 2)");
        assertTrue(analysis.noMatch());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWhereInSelectDifferentDataTypeValueUncompatibleDataTypes() throws Exception {
        analyze("select 'found' where 1 in (1, 'foo', 2)");
    }

    @Test
    public void testAggregationDistinct() {
        SelectAnalysis analysis = (SelectAnalysis) analyze("select count(distinct load['1']) from sys.nodes");

        assertTrue(analysis.hasAggregates());
        assertEquals(2, analysis.functions().size());

        Function collectionCount = getFunctionByName(CollectionCountFunction.NAME, analysis.functions());
        Function collectSet = getFunctionByName(CollectSetAggregation.NAME, analysis.functions());
        assertNotNull(collectionCount);
        assertNotNull(collectSet);

        List<Symbol> args = collectionCount.arguments();
        assertEquals(1, args.size());
        Function innerFunction = (Function) args.get(0);
        assertTrue(innerFunction.info().isAggregate());
        assertEquals(innerFunction.info().ident().name(), CollectSetAggregation.NAME);
        List<Symbol> innerArguments = innerFunction.arguments();
        assertThat(innerArguments.get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(((Reference) innerArguments.get(0)).info(), IsInstanceOf.instanceOf(ReferenceInfo.class));
        ReferenceInfo refInfo = ((Reference) innerArguments.get(0)).info();
        assertThat(refInfo.ident().columnIdent().name(), is("load"));
        assertThat(refInfo.ident().columnIdent().path().get(0), is("1"));

        assertSame(collectSet, innerFunction);
    }


    @Test
    public void testInsertMultipleValues() throws Exception {
        InsertAnalysis analysis = (InsertAnalysis)analyze(
                "insert into users (id, name, awesome) values (?, ?, ?), (?, ?, ?)",
                new Object[]{ 99, "Marvin", true, 42, "Deep Thought", false });
        assertThat(analysis.values().size(), is(2));

        assertThat(((LongLiteral)analysis.values().get(0).get(0)).value(), is(99l));
        assertThat(((StringLiteral)analysis.values().get(0).get(1)).value().utf8ToString(), is("Marvin"));
        assertThat(((BooleanLiteral)analysis.values().get(0).get(2)).value(), is(true));

        assertThat(((LongLiteral)analysis.values().get(1).get(0)).value(), is(42l));
        assertThat(((StringLiteral)analysis.values().get(1).get(1)).value().utf8ToString(), is("Deep Thought"));
        assertThat(((BooleanLiteral)analysis.values().get(1).get(2)).value(), is(false));
    }

    @Test
    public void testSelectWithObjectLiteral() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("1", 1.0);
        map.put("5", 2.5);
        map.put("15", 8.0);
        SelectAnalysis analysis = (SelectAnalysis) analyze("select id from sys.nodes where load=?",
                new Object[]{map});
        Function whereClause = analysis.whereClause().query();
        assertThat(whereClause.arguments().get(1), instanceOf(ObjectLiteral.class));
        assertTrue(((ObjectLiteral) whereClause.arguments().get(1)).value().equals(map));
    }

    @Test
    public void testLikeInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where name like 'foo'");

        assertNotNull(analysis.whereClause());
        Function whereClause = analysis.whereClause().query();
        assertEquals(LikeOperator.NAME, whereClause.info().ident().name());
        ImmutableList<DataType> argumentTypes = ImmutableList.<DataType>of(DataType.STRING, DataType.STRING);
        assertEquals(argumentTypes, whereClause.info().ident().argumentTypes());

        assertThat(whereClause.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertThat(whereClause.arguments().get(1), IsInstanceOf.instanceOf(StringLiteral.class));
        StringLiteral stringLiteral = (StringLiteral) whereClause.arguments().get(1);
        assertThat(stringLiteral.value(), is(new BytesRef(("foo"))));
    }

    @Test(expected = UnsupportedOperationException.class) // ESCAPE is not supported yet.
    public void testLikeEscapeInWhereQuery() {
        analyze("select * from sys.nodes where name like 'foo' escape 'o'");
    }

    @Test
    public void testLikeNoStringDataTypeInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where name like 1");

        // check if the implicit cast of the pattern worked
        ImmutableList<DataType> argumentTypes = ImmutableList.<DataType>of(DataType.STRING, DataType.STRING);
        Function whereClause = analysis.whereClause().query();
        assertEquals(argumentTypes, whereClause.info().ident().argumentTypes());
        assertThat(whereClause.arguments().get(1), IsInstanceOf.instanceOf(StringLiteral.class));
        StringLiteral stringLiteral = (StringLiteral) whereClause.arguments().get(1);
        assertThat(stringLiteral.value(), is(new BytesRef("1")));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testLikeReferenceInPatternInWhereQuery() {
        analyze("select * from sys.nodes where 1 like name");
    }

    @Test
    public void testLikeLongDataTypeInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where 1 like 2");

        // check if implicit cast worked of both, expression and pattern.
        Function function = (Function) analysis.functions().toArray()[0];
        assertEquals(LikeOperator.NAME, function.info().ident().name());
        ImmutableList<DataType> argumentTypes = ImmutableList.<DataType>of(DataType.STRING, DataType.STRING);
        assertEquals(argumentTypes, function.info().ident().argumentTypes());

        assertThat(function.arguments().get(0), IsInstanceOf.instanceOf(StringLiteral.class));
        assertThat(function.arguments().get(1), IsInstanceOf.instanceOf(StringLiteral.class));
        StringLiteral expressionLiteral = (StringLiteral) function.arguments().get(0);
        StringLiteral patternLiteral = (StringLiteral) function.arguments().get(1);
        assertThat(expressionLiteral.value(), is(new BytesRef("1")));
        assertThat(patternLiteral.value(), is(new BytesRef("2")));
    }

    @Test
    public void testIsNullInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where name is null");
        Function isNullFunction = (Function) analysis.functions().toArray()[0];

        assertThat(isNullFunction.info().ident().name(), is(IsNullPredicate.NAME));
        assertThat(isNullFunction.arguments().size(), is(1));
        assertThat(isNullFunction.arguments().get(0), IsInstanceOf.instanceOf(Reference.class));
        assertNotNull(analysis.whereClause());
    }

    @Test
    public void testNullIsNullInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where null is null");
        Function isNullFunction = (Function) analysis.functions().toArray()[0];
        assertThat(isNullFunction.arguments().get(0), IsInstanceOf.instanceOf(Null.class));
        assertFalse(analysis.whereClause().hasQuery());
        assertFalse(analysis.noMatch());
    }

    @Test
    public void testLongIsNullInWhereQuery() {
        Analysis analysis = analyze("select * from sys.nodes where 1 is null");
        Function isNullFunction = (Function) analysis.functions().toArray()[0];
        assertThat(isNullFunction.arguments().get(0), IsInstanceOf.instanceOf(LongLiteral.class));
        assertTrue(analysis.noMatch());
    }
}
