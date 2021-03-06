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

package eu.binjr.core.data.indexes.parser;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

public class ParsedEvent {
    private final ZonedDateTime timestamp;
    private final long lineNumber;
    private final Map<String, String> sections;
    private final String text;

    public ParsedEvent(long sequence, ZonedDateTime timestamp, String text, Map<String, String> sections) {
        this.lineNumber = sequence;
        this.text = text;
        this.timestamp = timestamp;
        this.sections = sections;
    }

    public long getSequence() {
        return lineNumber;
    }

    public String getText() {
        return text;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getSections() {
        return sections;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("ParsedEvent{");
        sb.append("timestamp=").append(timestamp);
        sb.append(", sections=").append(sections);
        sb.append(", text='").append(text).append('\'');
        sb.append('}');
        return sb.toString();
    }


}
