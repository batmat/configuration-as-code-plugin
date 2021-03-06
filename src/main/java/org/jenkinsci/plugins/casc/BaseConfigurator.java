package org.jenkinsci.plugins.casc;

import hudson.model.Describable;
import jenkins.model.Jenkins;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class BaseConfigurator<T> extends Configurator<T> {

    public Set<Attribute> describe() {

        Set<Attribute> attributes = new HashSet<>();

        final PropertyDescriptor[] properties = PropertyUtils.getPropertyDescriptors(getTarget());
        for (PropertyDescriptor p : properties) {
            final String name = p.getName();

            final Method setter = p.getWriteMethod();
            if (setter == null) continue; // read only
            if (setter.getAnnotation(Deprecated.class) != null) continue; // not actually public
            if (setter.getAnnotation(Restricted.class) != null) continue; // not actually public     - require access-modifier 1.12

            Class c = null;
            boolean multiple = false;

            // FIXME move this all into cleaner logic to discover property type
            Type type = setter.getGenericParameterTypes()[0];

            if (type instanceof GenericArrayType) {
                // type is a parameterized array: <Foo>[]
                multiple = true;
                GenericArrayType at = (GenericArrayType) type;
                type = at.getGenericComponentType();
            }
            else if (type instanceof ParameterizedType) {
                // type is parameterized `Some<Foo>`
                ParameterizedType pt = (ParameterizedType) type;

                Class rawType = (Class) pt.getRawType();
                if (Collection.class.isAssignableFrom(rawType)) {
                    // type is `Collection<Foo>`
                    multiple = true;
                }

                type = pt.getActualTypeArguments()[0];
                if (type instanceof WildcardType) {
                    // type is <? extends Foo>
                    type = ((WildcardType) type).getUpperBounds()[0];
                }
            }

            while (c == null) {
                if (type instanceof Class) {
                    c = (Class) type;
                } else if (type instanceof TypeVariable) {

                    // type is declared as parameterized type
                    // unfortunately, java reflection doesn't allow to get the actual parameter type
                    // so, if superclass it parameterized, we assume parameter type match
                    // i.e target is Foo extends AbtractFoo<Bar> with
                    // public abstract class AbtractFoo<T> { void setBar(T bar) }
                    final Type superclass = getTarget().getGenericSuperclass();
                    if (superclass instanceof ParameterizedType) {
                        final ParameterizedType psc = (ParameterizedType) superclass;
                        type = psc.getActualTypeArguments()[0];
                        continue;
                    } else {
                        c = (Class) ((TypeVariable) type).getBounds()[0];
                    }

                    TypeVariable tv = (TypeVariable) type;
                } else {
                    throw new IllegalStateException("Unable to detect type of attribute " + getTarget() + '#' + name);
                }
            }

            if (c.isArray()) {
                multiple = true;
                c = c.getComponentType();
            }

            Attribute attribute;
            if (!c.isPrimitive() && Modifier.isAbstract(c.getModifiers())) {
                if (!Describable.class.isAssignableFrom(c)) {
                    throw new IllegalStateException("Configuration-as-Code can't manage abstract attributes which are not Describable.");
                }
                attribute = new DescribableAttribute(p.getName(), c);
            } else {
                attribute = new Attribute(p.getName(), c);
            }
            attributes.add(attribute.withMultiple(multiple));

            // See https://github.com/jenkinsci/structs-plugin/pull/18
            final Symbol s = setter.getAnnotation(Symbol.class);
            // TODO record symbol as preferred name / alias for this attribute
        }
        return attributes;
    }

    protected void configure(Map config, T instance) throws Exception {
        final Set<Attribute> attributes = describe();

        for (Attribute attribute : attributes) {
            final String name = attribute.getName();
            if (config.containsKey(name)) {
                final Object sub = config.remove(name);
                if (attribute.isMultiple()) {
                    List values = new ArrayList<>();
                    for (Object o : (List) sub) {
                        Object value = Configurator.lookup(attribute.getType()).configure(o);
                        values.add(value);
                    }
                    attribute.setValue(instance, values);
                } else {
                    Object value = Configurator.lookup(attribute.getType()).configure(sub);
                    attribute.setValue(instance, value);
                }
            }
        }
        if (!config.isEmpty()) {
            final String invalid = StringUtils.join(config.keySet(), ',');
            throw new IllegalArgumentException("Invalid configuration elements for type " + instance.getClass() + ":" + invalid);
        }
    }
}
