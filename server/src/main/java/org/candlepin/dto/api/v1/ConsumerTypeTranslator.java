/**
 * Copyright (c) 2009 - 2017 Red Hat, Inc.
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
package org.candlepin.dto.api.v1;

import org.candlepin.dto.ModelTranslator;
import org.candlepin.dto.ObjectTranslator;
import org.candlepin.model.ConsumerType;



/**
 * The ConsumerTypeTranslator provides translation from ConsumerType model objects to
 * ConsumerTypeDTOs
 */
public class ConsumerTypeTranslator implements ObjectTranslator<ConsumerType, ConsumerTypeDTO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsumerTypeDTO translate(ConsumerType source) {
        return this.translate(null, source);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsumerTypeDTO translate(ModelTranslator translator, ConsumerType source) {
        return source != null ? this.populate(translator, source, new ConsumerTypeDTO()) : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsumerTypeDTO populate(ConsumerType source,
        ConsumerTypeDTO destination) {

        return this.populate(null, source, destination);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsumerTypeDTO populate(ModelTranslator translator, ConsumerType source,
        ConsumerTypeDTO destination) {

        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }

        if (destination == null) {
            throw new IllegalArgumentException("destination is null");
        }

        destination.setId(source.getId());
        destination.setLabel(source.getLabel());
        destination.setManifest(source.isManifest());

        return destination;
    }
}
