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

import java.util.Arrays;
import java.util.TreeSet;

import org.tzi.context.client.ContextManager;
import org.tzi.context.client.ContextManager.SubscriptionData;
import org.tzi.context.common.Context;

public class TestClass {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		SubscriptionData sd = SubscriptionData.create(null, "ctx1", "src1", "prp1", new TreeSet<String>(Arrays.asList(new String [] { "tag11", "tag12", "tag13" })));

		System.out.println(ContextManager.createSubscribeString(sd));
		
		sd.merge(SubscriptionData.create(null, "ctx1", "src1", "prp2", null));

		System.out.println(ContextManager.createSubscribeString(sd));

		sd.merge(SubscriptionData.create(null, "ctx1", "src2", "prp3", null));
		sd.merge(SubscriptionData.create(null, "ctx2", Context.ALL_SOURCES, Context.ALL_PROPERTIES, null));

		System.out.println(ContextManager.createSubscribeString(sd));
	}

}
