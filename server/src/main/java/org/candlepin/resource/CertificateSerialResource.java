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
package org.candlepin.resource;

import org.candlepin.dto.DTOFactory;
import org.candlepin.dto.api.APIDTOFactory;
import org.candlepin.dto.api.v1.CertificateSerialDTO;
import org.candlepin.model.CandlepinQuery;
import org.candlepin.model.CertificateSerial;
import org.candlepin.model.CertificateSerialCurator;

import com.google.inject.Inject;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;



/**
 * CertificateSerialResource
 */
@Path("/serials")
@Api(value = "serials", authorizations = { @Authorization("basic") })
public class CertificateSerialResource {
    private CertificateSerialCurator certificateSerialCurator;
    private DTOFactory dtoFactory;

    @Inject
    public CertificateSerialResource(CertificateSerialCurator certificateSerialCurator,
        APIDTOFactory dtoFactory) {

        this.certificateSerialCurator = certificateSerialCurator;
        this.dtoFactory = dtoFactory;
    }

    @ApiOperation(notes = "Retrieves a list of Certificate Serials", value = "getCertificateSerials",
        response = CertificateSerial.class, responseContainer = "list")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CandlepinQuery<CertificateSerialDTO> getCertificateSerials() {
        CandlepinQuery<CertificateSerial> query = this.certificateSerialCurator.listAll();
        return this.dtoFactory.<CertificateSerial, CertificateSerialDTO>transformQuery(query);
    }

    @ApiOperation(notes = "Retrieves single Certificate Serial", value = "getCertificateSerial")
    @GET
    @Path("/{serial_id}")
    @Produces(MediaType.APPLICATION_JSON)
    public CertificateSerialDTO getCertificateSerial(@PathParam("serial_id") Long serialId) {
        CertificateSerial serial = this.certificateSerialCurator.find(serialId);
        return this.dtoFactory.<CertificateSerial, CertificateSerialDTO>buildDTO(serial);
    }
}
