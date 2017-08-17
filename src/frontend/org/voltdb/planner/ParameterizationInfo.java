/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hsqldb_voltpatches.VoltXMLElement;
import org.voltdb.ParameterConverter;
import org.voltdb.ParameterSet;
import org.voltdb.VoltType;
import org.voltdb.utils.VoltTypeUtil;

/**
 * Given a SQL statements plan from HSQLDB, as our fake XML tree,
 * find all of the constant value expression and turn them into
 * parameters, mutating the fake XML tree in place.
 *
 * Returns the extracted params as strings because we don't 100%
 * HSQLDB's typing (ours is better). We do convert values typed as
 * null to Java null though.
 *
 */
class ParameterizationInfo {

    final VoltXMLElement parameterizedXmlSQL;
    final String[] paramLiteralValues;

    public ParameterizationInfo(VoltXMLElement parameterizedXmlSQL,
                                String[] paramLiteralValues)
    {
        assert(parameterizedXmlSQL != null);
        assert(paramLiteralValues != null);

        this.parameterizedXmlSQL = parameterizedXmlSQL;
        this.paramLiteralValues = paramLiteralValues;
    }

    public static ParameterizationInfo parameterize(VoltXMLElement xmlSQL) {
        assert(xmlSQL != null);

        VoltXMLElement parameterizedXmlSQL = xmlSQL.duplicate();

        List<VoltXMLElement> stmtNodes = new ArrayList<>();
        findAllStmtNodes(stmtNodes, parameterizedXmlSQL);

        Map<String, Integer> idToParamIndexMap = new HashMap<String, Integer>();
        List<String> paramValues = new ArrayList<String>();

        parameterizeStmtRecursively(stmtNodes, parameterizedXmlSQL, idToParamIndexMap, paramValues);

        ParameterizationInfo info = null;
        if(idToParamIndexMap.size() > 0) {
            info = new ParameterizationInfo(
                parameterizedXmlSQL,
                paramValues.toArray(new String[paramValues.size()]));
        }
        return info;
    }

    private static void findAllStmtNodes(List<VoltXMLElement> stmtNodes, VoltXMLElement node) {
        if ("select".equals(node.name)
                || "union".equals(node.name)
                || "update".equals(node.name)
                || "insert".equals(node.name)
                || "delete".equals(node.name)
                ) {
            stmtNodes.add(node);
        }

        for (VoltXMLElement child : node.children) {
            findAllStmtNodes(stmtNodes, child);
        }
    }

    public static void findUserParametersRecursively(final VoltXMLElement xmlSQL, Set<Integer> paramIds) {
        if (xmlSQL.name.equals("union")) {
            // UNION has its parameters on the individual selects level
            for (VoltXMLElement xmlChildSQL : xmlSQL.children) {
                findUserParametersRecursively(xmlChildSQL, paramIds);
            }
        } else {
            // find the parameters xml node
            for (VoltXMLElement child : xmlSQL.children) {
                if (! child.name.equals("parameters")) {
                    continue;
                }

                // "parameters" element contains all the parameter infomation for the query
                // also including its subqueries if it has.
                for (VoltXMLElement node : child.children) {
                    String idStr = node.attributes.get("id");
                    assert(idStr != null);
                    // ID attribute is assumed to be global unique per query.
                    // but for UNION query, "paramerters" are copied to each query level.
                    paramIds.add(Integer.parseInt(idStr));
                }

                // there is ONLY one parameters element per query
                break;
            }
        }
    }

    private static void parameterizeStmtRecursively(List<VoltXMLElement> stmtNodes,
                                    VoltXMLElement parameterizedXmlSQL,
                                    Map<String, Integer> idToParamIndexMap,
                                    List<String> paramValues) {
        List<VoltXMLElement> unionChildren = null;
        if (parameterizedXmlSQL.name.equals("union")) {
            // Set ops may may have their own nodes to parameterize (limit/offset)
            // in addition to children's nodes. Process children  first
            unionChildren = new ArrayList<VoltXMLElement>();
            Iterator<VoltXMLElement> iter = parameterizedXmlSQL.children.iterator();
            while (iter.hasNext()) {
                VoltXMLElement xmlChildSQL = iter.next();
                if ("select".equals(xmlChildSQL.name) || "union".equals(xmlChildSQL.name)) {
                    parameterizeStmtRecursively(stmtNodes, xmlChildSQL, idToParamIndexMap, paramValues);
                    // Temporarily remove it from the list
                    iter.remove();
                    unionChildren.add(xmlChildSQL);
                }
            }
        }
        // Parameterize itself
        parameterizeStmtItself(stmtNodes, parameterizedXmlSQL, idToParamIndexMap, paramValues);
        if (unionChildren != null) {
            // Add union children back
            parameterizedXmlSQL.children.addAll(unionChildren);
        }
    }

    private static void parameterizeStmtItself(List<VoltXMLElement> stmtNodes,
                                    VoltXMLElement parameterizedXmlSQL,
                                    Map<String, Integer> idToParamIndexMap,
                                    List<String> paramValues) {
        // find the parameters xml node
        VoltXMLElement paramsNode = null;
        for (VoltXMLElement child : parameterizedXmlSQL.children) {
            if (child.name.equals("parameters")) {
                paramsNode = child;
                break;
            }
        }

        // Some plans, like for SWAP TABLE can't use parameters
        if (paramsNode == null) {
            return;
        }

        // For the current implementation, to avoid confusion,
        // don't optimize plans that have user-supplied params.
        if (paramsNode.children.size() > 0) {
            return;
        }

        parameterizeRecursively(stmtNodes, parameterizedXmlSQL, paramsNode,
                idToParamIndexMap, paramValues);
    }

    private static void addParameterizedConstantToParameters(
            List<VoltXMLElement> stmtNodes,
            VoltXMLElement node,
            Map<String, Integer> idToParamIndexMap,
            List<String> paramValues) {

        String idStr = node.attributes.get("id");
        String typeStr = node.attributes.get("valuetype");

        int paramIndex = paramValues.size();

        // Later references to this value's id will re-use this same param index.
        idToParamIndexMap.put(idStr, paramIndex);
        VoltType vt = VoltType.typeFromString(typeStr);

        String value = null;
        if (vt != VoltType.NULL) {
            value = node.attributes.get("value");
        }
        paramValues.add(value);

        // If the type is NUMERIC from hsqldb, VoltDB has to decide its real type.
        // It's either INTEGER or DECIMAL according to the SQL Standard.
        // Thanks for Hsqldb 1.9, FLOAT literal values have been handled well with E sign.
        if (vt == VoltType.NUMERIC) {
            vt = VoltTypeUtil.getNumericLiteralType(VoltType.BIGINT, value);
        }

        node.attributes.put("valuetype", vt.getName());

        for (VoltXMLElement stmtNode : stmtNodes) {
            VoltXMLElement paramsNode = null;
            for (VoltXMLElement child : stmtNode.children) {
                if ("parameters".equals(child.name)) {
                    paramsNode = child;
                    break;
                }
            }

            if (paramsNode == null) {
                paramsNode = new VoltXMLElement("parameters");
                stmtNode.children.add(paramsNode);
            }

            VoltXMLElement paramIndexNode = new VoltXMLElement("parameter");
            paramIndexNode.attributes.put("index", String.valueOf(paramIndex));
            paramIndexNode.attributes.put("id", idStr);
            paramIndexNode.attributes.put("valuetype", vt.getName());
            paramsNode.children.add(paramIndexNode);
        }
    }

    private static void parameterizeRecursively(List<VoltXMLElement> stmtNodes,
                                        VoltXMLElement node,
                                        VoltXMLElement paramsNode,
                                        Map<String, Integer> idToParamIndexMap,
                                        List<String> paramValues) {
        if (node.name.equals("value")) {
            String idStr = node.attributes.get("id");
            assert(idStr != null);

            // A value id is currently a "string-formatted long", but there's no need to commit
            // to that format in this early processing -- here, the id just needs to be a unique
            // string for each parsed value. It allows hsql to replicate a parameter reference
            // within its parse trees without causing code like this to lose track of its identity.
            Integer paramIndex = idToParamIndexMap.get(idStr);
            if (paramIndex == null) {
                addParameterizedConstantToParameters(stmtNodes, node, idToParamIndexMap, paramValues);
            }

            // Assume that all values, whether or not their ids have been seen before, can
            // be considered planner-generated parameters (proxies for user-provided constants).
            // This is one simplification that leverages the fact that statements that came with
            // user-provided parameters were barred from being (further) parameterized.
            node.attributes.put("isparam", "true");
            node.attributes.put("isplannergenerated", "true");

            // Remove the "value" attribute -- this is the critical step to folding
            // different raw VoltXML trees into the same parameterized VoltXML tree.
            // The value differences are extracted into paramValues for future reference by:
            //     the execution engine which needs to substitute actual values for all parameters
            //         to run the query
            //     the index scan planner which may need to "bind" the parameters to their original
            //     values to apply indexes on expressions like "(colA + 2 * colB)" when used in a query
            //     like "... WHERE t2.colA + 2 * t2.colB > 3*t1.colC".
            node.attributes.remove("value");
        }

        for (VoltXMLElement child : node.children) {
            parameterizeRecursively(stmtNodes, child, paramsNode, idToParamIndexMap, paramValues);
        }
    }

    private static Object valueForStringWithType(String value, VoltType type) {
        if (type == VoltType.NULL) {
            return null;
        }

        // leverage existing (rather heavyweight) code to convert param types
        Object retval = ParameterConverter.tryToMakeCompatible(type.classFromType(), value);
        // check the result type in an assert
        assert(ParameterConverter.verifyParameterConversion(retval, type.classFromType()));
        return retval;
    }

    public ParameterSet extractedParamValues(VoltType[] parameterTypes) {
        assert(paramLiteralValues.length == parameterTypes.length);
        Object[] params = new Object[paramLiteralValues.length];

        // the extracted params are all strings at first.
        // after the planner infers their types, fix them up
        // the only exception is that nulls are Java NULL, and not the string "null".
        for (int i = 0; i < paramLiteralValues.length; i++) {
            params[i] = valueForStringWithType(paramLiteralValues[i], parameterTypes[i]);
        }
        return ParameterSet.fromArrayNoCopy(params);
    }
}
