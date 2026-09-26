package com.example.target;

/**
 * Calls an enhancement method of another class directly, the shape Hibernate's extended enhancement
 * leaves where a class outside the entity wrote one of its fields.
 */
public class EnhancedEntityCaller {
    public void assign(EnhancedEntityTarget entity, EnhancedAssociationTarget order) {
        entity.$$_hibernate_write_order(order);
    }
}
