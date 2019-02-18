/*
 *    Copyright 2017-2018 Frederic Thevenet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package eu.binjr.core.data.timeseries;

import eu.binjr.core.data.adapters.TimeSeriesBinding;

/**
 * A functional interface to be used as a factory for {@link TimeSeriesProcessor}
 *
 * @author Frederic Thevenet
 */
@FunctionalInterface
public interface TimeSeriesProcessorFactory {
    /**
     * Initializes a new instance of the {@link TimeSeriesProcessor} class from the provided {@link TimeSeriesBinding}
     *
     * @return a new instance of the {@link TimeSeriesProcessor} class
     */
    TimeSeriesProcessor create();
}
