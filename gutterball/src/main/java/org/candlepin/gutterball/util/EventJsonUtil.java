/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.gutterball.util;

import org.candlepin.gutterball.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helper class to build Event objects from event json
 */
public class EventJsonUtil {

    private static Logger log = LoggerFactory.getLogger(EventJsonUtil.class);

    private ObjectMapper mapper;

    public EventJsonUtil() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Event buildEvent(String eventJson) {
        try {
            Event result = mapper.readValue(eventJson, Event.class);
            result.setOriginalJson(eventJson);
            return result;
        }
        catch (Exception e) {
            log.error("Error parsing JSON from event");
            // TODO: use a candlepin exception
            throw new RuntimeException(e);
        }
    }
}
