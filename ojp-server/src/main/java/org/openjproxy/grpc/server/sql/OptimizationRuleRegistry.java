package org.openjproxy.grpc.server.sql;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;

import java.util.*;

/**
 * Registry of available optimization rules.
 * Allows configuration of which rules to apply.
 * 
 * Phase 2: Provides safe optimization rules for query rewriting.
 */
public class OptimizationRuleRegistry {
    
    private final Map<String, RelOptRule> ruleMap = new HashMap<>();
    
    /**
     * Creates a new registry with all available optimization rules.
     */
    public OptimizationRuleRegistry() {
        // Register safe optimization rules (Phase 2)
        registerRule("FILTER_REDUCE", CoreRules.FILTER_REDUCE_EXPRESSIONS);
        registerRule("PROJECT_REDUCE", CoreRules.PROJECT_REDUCE_EXPRESSIONS);
        registerRule("FILTER_MERGE", CoreRules.FILTER_MERGE);
        registerRule("PROJECT_MERGE", CoreRules.PROJECT_MERGE);
        registerRule("PROJECT_REMOVE", CoreRules.PROJECT_REMOVE);
        
        // Register aggressive optimization rules (Phase 3)
        registerRule("FILTER_INTO_JOIN", CoreRules.FILTER_INTO_JOIN);
        registerRule("JOIN_COMMUTE", CoreRules.JOIN_COMMUTE);
        registerRule("SUB_QUERY_REMOVE", CoreRules.SUB_QUERY_REMOVE);
    }
    
    /**
     * Registers a rule in the registry.
     * 
     * @param name Rule name
     * @param rule Rule instance
     */
    private void registerRule(String name, RelOptRule rule) {
        ruleMap.put(name, rule);
    }
    
    /**
     * Gets rules by their names.
     * 
     * @param names List of rule names
     * @return List of RelOptRule instances
     */
    public List<RelOptRule> getRulesByNames(List<String> names) {
        List<RelOptRule> rules = new ArrayList<>();
        for (String name : names) {
            RelOptRule rule = ruleMap.get(name);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }
    
    /**
     * Gets all safe rules (recommended for production).
     * These rules are conservative and unlikely to cause issues.
     * 
     * @return List of safe RelOptRule instances
     */
    public List<RelOptRule> getSafeRules() {
        return getRulesByNames(Arrays.asList(
            "FILTER_REDUCE",
            "PROJECT_REDUCE",
            "FILTER_MERGE",
            "PROJECT_MERGE",
            "PROJECT_REMOVE"
        ));
    }
    
    /**
     * Gets all available rules.
     * 
     * @return List of all RelOptRule instances
     */
    public List<RelOptRule> getAllRules() {
        return new ArrayList<>(ruleMap.values());
    }
    
    /**
     * Gets all available rule names.
     * 
     * @return Set of rule names
     */
    public Set<String> getAvailableRuleNames() {
        return new HashSet<>(ruleMap.keySet());
    }
}
