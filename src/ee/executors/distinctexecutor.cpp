/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB Inc. are licensed under the following
 * terms and conditions:
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "distinctexecutor.h"
#include "common/debuglog.h"
#include "common/common.h"
#include "common/tabletuple.h"
#include "common/FatalException.hpp"
#include "plannodes/distinctnode.h"
#include "storage/table.h"
#include "storage/temptable.h"
#include "storage/tableiterator.h"
#include "storage/tablefactory.h"

#include <set>
#include <cassert>

namespace voltdb {
namespace detail {

struct DistinctExecutorState
{   
    DistinctExecutorState(DistinctPlanNode* node, Table* targetTable):
        m_targetTableSchema(targetTable->schema()),
        m_childExecutor(NULL),
        m_distinctExpression(node->getDistinctExpression()),
        m_iterator(targetTable->iterator()),
        m_foundValues()
    {
        std::vector<AbstractPlanNode*>& children = node->getChildren();
        assert(children.size() == 1);
        m_childExecutor = children[0]->getExecutor();
        assert(m_childExecutor);
    }

    const TupleSchema* m_targetTableSchema; 
    AbstractExecutor* m_childExecutor;
    AbstractExpression* m_distinctExpression;
    TableIterator m_iterator;
    std::set<NValue, NValue::ltNValue> m_foundValues;
};
    
} // namespace detail
} // namespace voltdb

using namespace voltdb;

DistinctExecutor::DistinctExecutor(VoltDBEngine *engine, AbstractPlanNode* abstract_node)
    : AbstractExecutor(engine, abstract_node),
    distinct_column_type(VALUE_TYPE_INVALID), m_state() {
}
    
bool DistinctExecutor::p_init(AbstractPlanNode*,
                              TempTableLimits* limits)
{
    VOLT_DEBUG("init Distinct Executor");
    DistinctPlanNode* node = dynamic_cast<DistinctPlanNode*>(m_abstractNode);
    assert(node);
    //
    // Create a duplicate of input table
    //
    if (!node->isInline()) {
        assert(node->getInputTables().size() == 1);
        assert(node->getInputTables()[0]->columnCount() > 0);
        assert(node->getChildren()[0] != NULL);

        node->
            setOutputTable(TableFactory::
                           getCopiedTempTable(node->databaseId(),
                                              node->getInputTables()[0]->name(),
                                              node->getInputTables()[0],
                                              limits));
    }
    return (true);
}

bool DistinctExecutor::p_execute(const NValueArray &params) {
    DistinctPlanNode* node = dynamic_cast<DistinctPlanNode*>(m_abstractNode);
    assert(node);
    Table* output_table = node->getOutputTable();
    assert(output_table);
    Table* input_table = node->getInputTables()[0];
    assert(input_table);

    TableIterator iterator = input_table->iterator();
    TableTuple tuple(input_table->schema());

    std::set<NValue, NValue::ltNValue> found_values;
    while (iterator.next(tuple)) {
        //
        // Check whether this value already exists in our list
        //
        NValue tuple_value = node->getDistinctExpression()->eval(&tuple, NULL);
        if (found_values.find(tuple_value) == found_values.end()) {
            found_values.insert(tuple_value);
            if (!output_table->insertTuple(tuple)) {
                VOLT_ERROR("Failed to insert tuple from input table '%s' into"
                           " output table '%s'",
                           input_table->name().c_str(),
                           output_table->name().c_str());
                return false;
            }
        }
    }

    return true;
}


//@TODO pullexec prototype
TableTuple DistinctExecutor::p_next_pull() {
    TableTuple tuple(m_state->m_targetTableSchema);
    bool exists = true;
    for (tuple = m_state->m_childExecutor->p_next_pull(); 
         tuple.isNullTuple() == false && exists; 
         tuple = m_state->m_childExecutor->p_next_pull()) {
        NValue tuple_value = m_state->m_distinctExpression->eval(&tuple, NULL);
        exists = (m_state->m_foundValues.find(tuple_value) != m_state->m_foundValues.end());
        if (!exists) {
            m_state->m_foundValues.insert(tuple_value);
        }
    }
     
    if (tuple.isNullTuple() == true) {
        // We exhausted the input table
        tuple = TableTuple(m_state->m_targetTableSchema);
    }
    return tuple;
}

void DistinctExecutor::p_pre_execute_pull(const NValueArray &params) {
    //
    // Initialize itself
    //
    DistinctPlanNode* node = dynamic_cast<DistinctPlanNode*>(m_abstractNode);
    assert(node);
    Table* input_table = node->getInputTables()[0];
    assert(input_table);
    
    m_state.reset(new detail::DistinctExecutorState(node, input_table)); 
}

bool DistinctExecutor::support_pull() const {
    return true;
}
