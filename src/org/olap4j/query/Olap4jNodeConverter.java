/*
// $Id:$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2007-2009 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package org.olap4j.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.olap4j.Axis;
import org.olap4j.mdx.AxisNode;
import org.olap4j.mdx.CallNode;
import org.olap4j.mdx.CubeNode;
import org.olap4j.mdx.DimensionNode;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.LiteralNode;
import org.olap4j.mdx.MemberNode;
import org.olap4j.mdx.ParseTreeNode;
import org.olap4j.mdx.SelectNode;
import org.olap4j.mdx.Syntax;
import org.olap4j.metadata.Member;

/**
 * Utility class to convert a Query object to a SelectNode.
 */
abstract class Olap4jNodeConverter {

    public static SelectNode toOlap4j(Query query) {
        List<IdentifierNode> list = Collections.emptyList();
        List<ParseTreeNode> withList = Collections.emptyList();
        List<QueryAxis> axisList = new ArrayList<QueryAxis>();
        axisList.add(query.getAxes().get(Axis.COLUMNS));
        axisList.add(query.getAxes().get(Axis.ROWS));

        AxisNode filterAxis = null;
        if (query.getAxes().containsKey(Axis.FILTER)) {
            final QueryAxis axis = query.getAxes().get(Axis.FILTER);
            if (!axis.dimensions.isEmpty()) {
                filterAxis = toOlap4j(axis);
            }
        }
        return new SelectNode(
            null,
            withList,
            toOlap4j(axisList),
            new CubeNode(
                null,
                query.getCube()),
            filterAxis,
            list);
    }

    private static CallNode generateSetCall(ParseTreeNode... args) {
        return
            new CallNode(
                null,
                "{}",
                Syntax.Braces,
                args);
    }

    private static CallNode generateListSetCall(List<ParseTreeNode> cnodes) {
        return
            new CallNode(
                null,
                "{}",
                Syntax.Braces,
                cnodes);
    }

    private static CallNode generateListTupleCall(List<ParseTreeNode> cnodes) {
        return
            new CallNode(
                null,
                "()",
                Syntax.Parentheses,
                cnodes);
    }

    protected static CallNode getMemberSet(QueryDimension dimension) {
        return
            new CallNode(
              null,
              "{}",
              Syntax.Braces,
              toOlap4j(dimension));
    }

    protected static CallNode crossJoin(
        QueryDimension dim1,
        QueryDimension dim2)
    {
        return
            new CallNode(
                null,
                "CrossJoin",
                Syntax.Function,
                getMemberSet(dim1),
                getMemberSet(dim2));
    }

    //
    // This method merges the selections into a single
    // MDX axis selection.  Right now we do a simple
    // crossjoin
    //
    private static AxisNode toOlap4j(QueryAxis axis) {
        CallNode callNode = null;
        int numDimensions = axis.getDimensions().size();
        if (axis.getLocation() == Axis.FILTER) {
            // need a tuple
            // need a crossjoin
            List<ParseTreeNode> members = new ArrayList<ParseTreeNode>();
            for (int dimNo = 0; dimNo < numDimensions; dimNo++) {
                QueryDimension dimension =
                    axis.getDimensions().get(dimNo);
                if (dimension.getSelections().size() == 1) {
                    members.addAll(toOlap4j(dimension));
                }
            }
            callNode = generateListTupleCall(members);
        } else if (numDimensions == 1) {
            QueryDimension dimension = axis.getDimensions().get(0);
            List<ParseTreeNode> members = toOlap4j(dimension);
            callNode = generateListSetCall(members);
        } else if (numDimensions == 2) {
            callNode =
                crossJoin(
                    axis.getDimensions().get(0),
                    axis.getDimensions().get(1));
        } else {
            // need a longer crossjoin
            // start from the back of the list;
            List<QueryDimension> dims = axis.getDimensions();
            callNode = getMemberSet(dims.get(dims.size() - 1));
            for (int i = dims.size() - 2; i >= 0; i--) {
                CallNode memberSet = getMemberSet(dims.get(i));
                callNode =
                    new CallNode(
                        null,
                        "CrossJoin",
                        Syntax.Function,
                        memberSet,
                        callNode);
            }
        }
        return new AxisNode(
            null,
            axis.isNonEmpty(),
            axis.getLocation(),
            new ArrayList<IdentifierNode>(),
            callNode);
    }

    private static List<ParseTreeNode> toOlap4j(QueryDimension dimension) {
        List<ParseTreeNode> members = new ArrayList<ParseTreeNode>();
        for (Selection selection : dimension.getSelections()) {
            members.add(toOlap4j(selection));
        }
        if (dimension.getSortOrder() != null) {
            CallNode currentMemberNode = new CallNode(
                null,
                "CurrentMember",
                Syntax.Property,
                new DimensionNode(null,dimension.getDimension()));
            CallNode currentMemberNameNode = new CallNode(
                null,
                "Name",
                Syntax.Property,
                currentMemberNode);
            List<ParseTreeNode> orderedList = new ArrayList<ParseTreeNode>();
            orderedList.add(
                new CallNode(
                    null,
                    "Order",
                    Syntax.Function,
                    generateListSetCall(members),
                    currentMemberNameNode,
                    LiteralNode.createSymbol(
                        null, dimension.getSortOrder().name())));
            return orderedList;
        } else {
            return members;
        }
    }

    private static ParseTreeNode toOlap4j(Selection selection) {
        try {
            return toOlap4j(selection.getMember(), selection.getOperator());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static ParseTreeNode toOlap4j(
        Member member,
        Selection.Operator oper)
    {
        ParseTreeNode node = null;
        try {
            switch (oper) {
            case MEMBER:
                node = new MemberNode(null, member);
                break;
            case SIBLINGS:
                node =
                    new CallNode(
                        null,
                        "Siblings",
                        Syntax.Property,
                        new MemberNode(null, member));
                break;
            case CHILDREN:
                node =
                    new CallNode(
                        null,
                        "Children",
                        Syntax.Property,
                        new MemberNode(null, member));
                break;
            case INCLUDE_CHILDREN:
                node =
                    generateSetCall(
                        new MemberNode(null, member),
                        toOlap4j(member, Selection.Operator.CHILDREN));
                break;
            case DESCENDANTS:
                node =
                    new CallNode(
                        null,
                        "Descendants",
                        Syntax.Function,
                        new MemberNode(null, member));
                break;
            case ANCESTORS:
                node =
                    new CallNode(
                        null,
                        "Ascendants",
                        Syntax.Function,
                        new MemberNode(null, member));
                break;
            default:
                System.out.println("NOT IMPLEMENTED: " + oper);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return node;
    }

    private static List<AxisNode> toOlap4j(List<QueryAxis> axes) {
        final ArrayList<AxisNode> axisList = new ArrayList<AxisNode>();
        for (QueryAxis axis : axes) {
            axisList.add(toOlap4j(axis));
        }
        return axisList;
    }
}

// End Olap4jNodeConverter.java
