/**
 * Mycat rewriter rules for Calcite.
 */
@org.immutables.value.Value.Style(
    get = {"is*", "get*"},
    defaults = @org.immutables.value.Value.Immutable(builder = true, singleton = true)
)
package io.mycat.calcite.rewriter;
