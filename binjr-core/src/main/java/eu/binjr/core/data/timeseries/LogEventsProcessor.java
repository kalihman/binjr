/*
 *    Copyright 2020 Frederic Thevenet
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

/*
 *    Copyright 2020 Frederic Thevenet
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

import java.util.*;

public class LogEventsProcessor extends TimeSeriesProcessor<LogEvent> {

    private final Map<String, Collection<FacetEntry>> facetResults = new HashMap<>();
    private int totalHits = 0;
    private int hitsPerPage = 0;

    @Override
    protected LogEvent computeMinValue() {
        return null;
    }

    @Override
    protected LogEvent computeAverageValue() {
        return null;
    }

    @Override
    protected LogEvent computeMaxValue() {
        return null;
    }

    public Map<String, Collection<FacetEntry>> getFacetResults() {
        return facetResults;
    }

    public void addFacetResults(String name, Collection<FacetEntry> synthesis) {
        this.facetResults.put(name, synthesis);
    }

    public int getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(int totalHits) {
        this.totalHits = totalHits;
    }

    public void setHitsPerPage(int hitsPerPage) {
        this.hitsPerPage = hitsPerPage;
    }

    public int getHitsPerPage() {
        return hitsPerPage;
    }

}
