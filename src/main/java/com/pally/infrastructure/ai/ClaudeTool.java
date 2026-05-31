package com.pally.infrastructure.ai;

import java.util.Map;

/**
 * A deterministic tool the model can call during a completion.
 * Every implementation must be side-effect-free, sub-millisecond,
 * and never delegate to another LLM call.
 */
public interface ClaudeTool {

    /** Matches the {@code name} field in the Anthropic tools array. */
    String name();

    /** Short, accurate description the model uses to decide when to call this tool. */
    String description();

    /**
     * JSON Schema object describing the tool's input parameters.
     * Follows Anthropic's {@code input_schema} format (type=object, properties, required).
     */
    Map<String, Object> inputSchema();

    /**
     * Execute the tool with the parsed input the model provided.
     * Return a string result the model will see as a {@code tool_result} block.
     * Throw on invalid input so the model can handle the error gracefully.
     */
    String execute(Map<String, Object> input) throws CalculatorTool.CalculatorException;
}
