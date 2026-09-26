package com.example.target;

/**
 * Declares the shapes Hibernate's bytecode enhancement adds to an entity (ADR 0047): a reader and a
 * writer per persistent field and a fixed tracker method, each named with the {@code $$_hibernate_}
 * prefix, and the entity's own accessors calling them in place of the field. The writer calls the
 * other side of an association, as a bidirectional association's does.
 */
public class EnhancedEntityTarget {
    private String name;
    private EnhancedAssociationTarget order;
    private Object interceptor;

    public String getName() {
        return $$_hibernate_read_name();
    }

    public void setOrder(EnhancedAssociationTarget order) {
        $$_hibernate_write_order(order);
    }

    public String $$_hibernate_read_name() {
        if (interceptor != null) {
            return String.valueOf(interceptor);
        }
        return name;
    }

    public void $$_hibernate_write_order(EnhancedAssociationTarget order) {
        this.order = order;
        if (order != null) {
            order.addItem(this);
        }
    }

    public void $$_hibernate_trackChange(String attribute) {
        if (attribute == null) {
            return;
        }
        name = attribute;
    }
}
