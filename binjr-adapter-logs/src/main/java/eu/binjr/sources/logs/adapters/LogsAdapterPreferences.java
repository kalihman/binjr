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

package eu.binjr.sources.logs.adapters;


import com.google.gson.Gson;
import eu.binjr.common.preferences.ObservablePreference;
import eu.binjr.core.data.adapters.DataAdapter;
import eu.binjr.core.data.adapters.DataAdapterPreferences;
import eu.binjr.core.data.indexes.parser.profile.BuiltInParsingProfile;
import eu.binjr.core.data.indexes.parser.profile.CustomParsingProfile;
import eu.binjr.core.data.indexes.parser.profile.ParsingProfile;

public class LogsAdapterPreferences extends DataAdapterPreferences {
    private static final Gson gson = new Gson();

    public LogsAdapterPreferences(Class<? extends DataAdapter<?>> dataAdapterClass) {
        super(dataAdapterClass);
    }

    public ObservablePreference<Number> defaultTextViewFontSize = integerPreference("defaultTextViewFontSize", 10);

    public ObservablePreference<String[]> folderFilters = objectPreference(String[].class,
            "folderFilters",
            new String[]{"*"},
            gson::toJson,
            s -> gson.fromJson(s, String[].class));

    public ObservablePreference<String[]> fileExtensionFilters = objectPreference(String[].class,
            "extensionFilters",
            new String[]{"*.log", "*.txt"},
            gson::toJson,
            s -> gson.fromJson(s, String[].class));

    public ObservablePreference<String> mostRecentlyUsedParsingProfile =
            stringPreference("mostRecentlyUsedParsingProfile", BuiltInParsingProfile.ISO.name());

    public ObservablePreference<ParsingProfile[]> userParsingProfiles =
            objectPreference(ParsingProfile[].class,
                    "userParsingProfiles",
                    new ParsingProfile[0],
                    s -> gson.toJson(s),
                    s -> gson.fromJson(s, CustomParsingProfile[].class)
            );
}
