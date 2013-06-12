/*
   Copyright 2007-2013 Hendrik Iben, University Bremen

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.tzi.context;

import org.tzi.context.common.Context;
import org.tzi.context.common.ContextElement;
import org.tzi.context.common.ContextListener;
import org.tzi.context.common.ContextListenerInterface;

public class ContextDemo {

	private static class DemoListener implements ContextListener {
		private String name;
		private ContextListenerInterface properties = null;
		
		public DemoListener(String name) {
			this.name = name;
		}
		
		public ContextListenerInterface getProperties() {
			return properties;
		}
		
		public void setProperties(ContextListenerInterface clp) {
			this.properties = clp;
		}
		
		public void processContext(Context ctx, ContextElement ce) {
			System.out.println(name + ": Context-Change: " + ce);
		}

		public void propertyRemoved(Context ctx, String source, String property) {
			System.out.println(name + ": Property removed: " + source + "." + property);
		}

		public void sourceRemoved(Context ctx, String source) {
			System.out.println(name + ": Source removed: " + source);
		}
		
		public void sourceAdded(Context ctx, String source, String property) {
			System.out.println(name + ": Source added: " + source + " with property " + property);
		}

		public void propertyAdded(Context ctx, String source, String property) {
			System.out.println(name + ": Property added for source : " + source + ", property is " + property);
		}
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Context ctx = new Context("World");
		
		ctx.printContext();
		
		ContextElement ceA = new ContextElement("SourceA", "PropertyA", "A", 0, true, Context.T_ARTIFICIAL, Context.T_VIRTUAL);
		ContextElement ceB = new ContextElement("SourceB", "PropertyB", "B", 2, false, Context.T_ARTIFICIAL, Context.T_IMPLICIT_ACTION);
		ContextElement ceA2 = new ContextElement("SourceA", "PropertyA", "A2", 4, false, Context.T_ARTIFICIAL, Context.T_ENVIRONMENT);
		ContextElement ceB2 = new ContextElement("SourceB", "PropertyB", "B2", 8, true, Context.T_ARTIFICIAL, Context.T_USER_ACTION);
		ContextElement ceB3 = new ContextElement("SourceB", "PropertyB.1", "42", 12, true, Context.T_ENVIRONMENT);
		
		ContextListenerInterface clpa = Context.createCLPSources("SourceA");
		ContextListenerInterface clpb = Context.createCLPFor("SourceB", Context.ALL_PROPERTIES, Context.T_ARTIFICIAL);
		clpb.setNewSourcePolicy(ContextListenerInterface.NewElementPolicy.IfMatch);

		DemoListener dla = new DemoListener("AL");
		DemoListener dlb = new DemoListener("BL");
		
		dla.setProperties(clpa);
		dlb.setProperties(clpb);
		
		ctx.addContextListener(dla);
		ctx.addContextListener(dlb);

		for(ContextElement ce : new ContextElement [] { ceA, ceB, ceA2, ceB2, ceB3 }) {
			System.out.println("Adding context: " + ce);
			ctx.mergeContextElement(ce);
			ctx.printContext();
		}
		
		ctx.removeSourceProperty("SourceB", "PropertyB.1");
		ctx.printContext();
	}

}
