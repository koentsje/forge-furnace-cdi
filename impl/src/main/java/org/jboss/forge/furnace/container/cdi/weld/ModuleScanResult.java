package org.jboss.forge.furnace.container.cdi.weld;

import java.net.URL;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.jboss.weld.resources.spi.ResourceLoader;

public class ModuleScanResult
{
   private ResourceLoader loader;
   private List<URL> resourceUrls;
   private Collection<String> classes;

   public ModuleScanResult(ResourceLoader loader, List<URL> discoveredResourceUrls, Collection<String> discoveredClasses)
   {
      this.loader = loader;
      this.resourceUrls = discoveredResourceUrls;
      this.classes = discoveredClasses;
   }

   public Collection<String> getDiscoveredClasses()
   {
      return classes;
   }

   public List<URL> getDiscoveredResourceUrls()
   {
      return resourceUrls;
   }

   public ResourceLoader getResourceLoader()
   {
      return loader;
   }

   @Override
   public String toString()
   {
      StringBuilder result = new StringBuilder();
      if (resourceUrls != null)
      {
         Iterator<URL> iterator = resourceUrls.iterator();
         while (iterator.hasNext())
         {
            String url = iterator.next().toString();
            result.append(url).append("\n");
         }
      }
      if (classes != null)
      {
         result.append("\n");
         Iterator<String> iterator = classes.iterator();
         while (iterator.hasNext())
         {
            String type = iterator.next();
            result.append(type).append("\n");
         }
      }
      return result.toString();
   }
}
