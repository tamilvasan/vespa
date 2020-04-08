// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "dense_lambda_peek_optimizer.h"
#include "dense_tensor_view.h"
#include <vespa/eval/eval/value.h>
#include <vespa/eval/eval/node_tools.h>
#include <vespa/eval/eval/basic_nodes.h>
#include <vespa/eval/eval/operator_nodes.h>
#include <vespa/eval/eval/tensor_nodes.h>
#include <vespa/eval/eval/llvm/compile_cache.h>

using namespace vespalib::eval;
using namespace vespalib::eval::nodes;

namespace vespalib::tensor {

namespace {

/**
 * A 'simple peek' is defined to be a function containing a single
 * tensor peek operation where all dimension indexes can be determined
 * up front (only depend on the dimension indexes of the tensor to be
 * created by the enclosing tensor lambda). The expressions used to
 * calculate the cell addresses also need to be compilable.
 **/
bool is_simple_peek(const Function &function, size_t num_dims) {
    auto peek = as<TensorPeek>(function.root());
    if (peek && function.num_params() == (num_dims + 1)) {
        auto param = as<Symbol>(peek->get_child(0));
        if (param && (param->id() == num_dims)) {
            for (size_t i = 1; i < peek->num_children(); ++i) {
                const Node &dim_expr = peek->get_child(i);
                if (NodeTools::min_num_params(dim_expr) > num_dims) {
                    return false;
                }
                if (CompiledFunction::detect_issues(dim_expr)) {
                    return false;
                }
            }
            return true;
        }
    }
    return false;
}

} // namespace vespalib::tensor::<unnamed>

const TensorFunction &
DenseLambdaPeekOptimizer::optimize(const TensorFunction &expr, Stash &)
{
    auto lambda = as<tensor_function::Lambda>(expr);
    if (lambda && is_simple_peek(lambda->lambda(), expr.result_type().dimensions().size())) {
        fprintf(stderr, "simple peek detected!\n");
    }
    return expr;
}

} // namespace vespalib::tensor
