/*
 * Copyright 2012 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.furnace.container.cdi.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.ProcessProducer;

import org.jboss.forge.furnace.addons.Addon;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.container.cdi.services.ExportedInstanceInjectionPoint;
import org.jboss.forge.furnace.container.cdi.services.ExportedInstanceLazyLoader;
import org.jboss.forge.furnace.container.cdi.util.BeanBuilder;
import org.jboss.forge.furnace.container.cdi.util.BeanManagerUtils;
import org.jboss.forge.furnace.container.cdi.util.ContextualLifecycle;
import org.jboss.forge.furnace.container.cdi.util.Types;
import org.jboss.forge.furnace.exception.ContainerException;
import org.jboss.forge.furnace.util.ClassLoaders;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a> *
 */
public class ContainerServiceExtension implements Extension
{
   private static Logger logger = Logger.getLogger(ContainerServiceExtension.class.getName());

   private Map<Class<?>, AnnotatedType<?>> services = new HashMap<Class<?>, AnnotatedType<?>>();
   private Map<InjectionPoint, Class<?>> requestedServices = new HashMap<InjectionPoint, Class<?>>();
   private Map<InjectionPoint, ServiceLiteral> requestedServiceLiterals = new HashMap<InjectionPoint, ServiceLiteral>();

   private Addon container;
   private Addon addon;

   public ContainerServiceExtension()
   {
      // For proxying.
   }

   public ContainerServiceExtension(Addon container, Addon addon)
   {
      this.container = container;
      this.addon = addon;
   }

   public void processRemoteServiceTypes(@Observes ProcessAnnotatedType<?> event) throws InstantiationException,
            IllegalAccessException
   {
      Class<?> type = event.getAnnotatedType().getJavaClass();
      if (addon.getClassLoader().equals(type.getClassLoader())
               && !container.getClassLoader().equals(type.getClassLoader())
               && !(Modifier.isAbstract(type.getModifiers())
               || Modifier.isInterface(type.getModifiers())))
      {
         services.put(event.getAnnotatedType().getJavaClass(), event.getAnnotatedType());
         logger.fine("Addon [" + addon + "] requires service type [" + type.getName() + "] in ClassLoader ["
                  + type.getClassLoader() + "]");
      }
   }

   public void processRemoteInjectionPointConsumer(@Observes ProcessInjectionPoint<?, ?> event, BeanManager manager)
   {
      Annotated annotated = event.getInjectionPoint().getAnnotated();

      Class<?> injectionPointDeclaringType = Types.toClass(event.getInjectionPoint().getMember().getDeclaringClass());
      Class<?> injectionBeanValueType = Types.toClass(annotated.getBaseType());

      Class<?> injectionPointConsumingType = null;
      if (event.getInjectionPoint().getBean() != null)
         injectionPointConsumingType = event.getInjectionPoint().getBean().getBeanClass();
      else
         injectionPointConsumingType = injectionPointDeclaringType;

      if (Instance.class.isAssignableFrom(injectionBeanValueType))
      {
         Type type = event.getInjectionPoint().getType();
         if (type instanceof ParameterizedType)
         {
            Type[] types = ((ParameterizedType) type).getActualTypeArguments();
            injectionBeanValueType = Types.toClass(types[0]);
         }
      }

      if (!isBeanConsumerLocal(injectionPointConsumingType, injectionBeanValueType)
               && !ClassLoaders.containsClass(container.getClassLoader(), injectionBeanValueType))
      {
         ServiceLiteral serviceLiteral = new ServiceLiteral();
         event.setInjectionPoint(new ExportedInstanceInjectionPoint(event.getInjectionPoint(), serviceLiteral));
         requestedServices.put(event.getInjectionPoint(), injectionBeanValueType);
         requestedServiceLiterals.put(event.getInjectionPoint(), serviceLiteral);
      }
   }

   public void processProducerHooks(@Observes ProcessProducer<?, ?> event, BeanManager manager)
   {
      Class<?> type = Types.toClass(event.getAnnotatedMember().getJavaMember());
      ClassLoader classLoader = type.getClassLoader();
      if (classLoader != null && addon.getClassLoader().equals(classLoader))
      {
         // I am not sure what this is doing anymore.
         services.put(type, manager.createAnnotatedType(type));
      }
   }

   public void wireCrossContainerServices(@Observes AfterBeanDiscovery event, final BeanManager manager)
   {
      // needs to happen in the addon that is requesting the service
      for (final Entry<InjectionPoint, Class<?>> entry : requestedServices.entrySet())
      {
         final InjectionPoint injectionPoint = entry.getKey();
         final Annotated annotated = injectionPoint.getAnnotated();
         final Member member = injectionPoint.getMember();

         Class<?> beanClass = entry.getValue();
         Set<Type> typeClosure = annotated.getTypeClosure();
         Set<Type> beanTypeClosure = new LinkedHashSet<Type>();
         for (Type type : typeClosure)
         {
            beanTypeClosure.add(reifyWildcardsToObjects(type));
         }

         Bean<?> serviceBean = new BeanBuilder<Object>(manager)
                  .beanClass(beanClass)
                  .types(beanTypeClosure)
                  .beanLifecycle(new ContextualLifecycle<Object>()
                  {
                     @Override
                     public void destroy(Bean<Object> bean, Object instance, CreationalContext<Object> creationalContext)
                     {
                        creationalContext.release();
                     }

                     @Override
                     public Object create(Bean<Object> bean, CreationalContext<Object> creationalContext)
                     {
                        Class<?> serviceType = null;
                        if (member instanceof Method)
                        {
                           if (annotated instanceof AnnotatedParameter)
                           {
                              serviceType = ((Method) member).getParameterTypes()[((AnnotatedParameter<?>) annotated)
                                       .getPosition()];
                           }
                           else
                              serviceType = ((Method) member).getReturnType();
                        }
                        else if (member instanceof Field)
                        {
                           serviceType = ((Field) member).getType();
                        }
                        else if (member instanceof Constructor)
                        {
                           if (annotated instanceof AnnotatedParameter)
                           {
                              serviceType = ((Constructor<?>) member).getParameterTypes()[((AnnotatedParameter<?>) annotated)
                                       .getPosition()];
                           }
                        }
                        else
                        {
                           throw new ContainerException(
                                    "Cannot handle producer for non-Field and non-Method member type: " + member);
                        }

                        return ExportedInstanceLazyLoader.create(
                                 BeanManagerUtils.getContextualInstance(manager, AddonRegistry.class),
                                 injectionPoint,
                                 serviceType
                                 );
                     }
                  })
                  .qualifiers(requestedServiceLiterals.get(injectionPoint))
                  .create();

         event.addBean(serviceBean);
      }
   }

   private Type reifyWildcardsToObjects(final Type type)
   {
      if (type instanceof ParameterizedType)
      {
         final Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
         if (arguments != null)
         {
            boolean modified = false;
            final Type[] reifiedArguments = new Type[arguments.length];
            for (int i = 0; i < arguments.length; i++)
            {
               final Type argument = arguments[i];
               Type reified = argument;
               if (argument instanceof WildcardType)
               {
                  if (((WildcardType) argument).getLowerBounds().length == 0)
                  {
                     Type[] upperBounds = ((WildcardType) argument).getUpperBounds();
                     if (upperBounds.length == 1 && upperBounds[0].equals(Object.class))
                        reified = Object.class;
                  }

               }
               else
               {
                  reified = reifyWildcardsToObjects(argument);
               }

               reifiedArguments[i] = reified;

               if (reified != argument)
                  modified = true;
            }

            if (modified)
            {
               return new ParameterizedType()
               {
                  @Override
                  public Type getRawType()
                  {
                     return ((ParameterizedType) type).getRawType();
                  }

                  @Override
                  public Type getOwnerType()
                  {
                     return ((ParameterizedType) type).getOwnerType();
                  }

                  @Override
                  public Type[] getActualTypeArguments()
                  {
                     return reifiedArguments;
                  }
               };
            }
         }
      }
      return type;
   }

   /*
    * Helpers
    */
   public Set<Class<?>> getServices()
   {
      return services.keySet();
   }

   private boolean isBeanConsumerLocal(Class<?> reference, Class<?> type)
   {
      ClassLoader referenceLoader = reference.getClassLoader();
      ClassLoader typeLoader = type.getClassLoader();
      if (referenceLoader != null && referenceLoader.equals(typeLoader))
         return true;
      return false;
   }
}
