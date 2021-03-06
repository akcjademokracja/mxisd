/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.controller.v1

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.kamax.mxisd.lookup.ALookupRequest
import io.kamax.mxisd.lookup.BulkLookupRequest
import io.kamax.mxisd.lookup.SingleLookupRequest
import io.kamax.mxisd.lookup.ThreePidMapping
import io.kamax.mxisd.lookup.strategy.LookupStrategy
import io.kamax.mxisd.signature.SignatureManager
import org.apache.commons.lang.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

import javax.servlet.http.HttpServletRequest

import static org.springframework.web.bind.annotation.RequestMethod.GET
import static org.springframework.web.bind.annotation.RequestMethod.POST

@RestController
class MappingController {

    private Logger log = LoggerFactory.getLogger(MappingController.class)
    private JsonSlurper json = new JsonSlurper()

    @Autowired
    private LookupStrategy strategy

    @Autowired
    private SignatureManager signMgr

    private void setRequesterInfo(ALookupRequest lookupReq, HttpServletRequest req) {
        lookupReq.setRequester(req.getRemoteAddr())
        String xff = req.getHeader("X-FORWARDED-FOR")
        lookupReq.setRecursive(StringUtils.isNotBlank(xff))
        if (lookupReq.isRecursive()) {
            lookupReq.setRecurseHosts(Arrays.asList(xff.split(",")))
        }
    }

    @RequestMapping(value = "/_matrix/identity/api/v1/lookup", method = GET)
    String lookup(HttpServletRequest request, @RequestParam String medium, @RequestParam String address) {
        SingleLookupRequest lookupRequest = new SingleLookupRequest()
        setRequesterInfo(lookupRequest, request)
        lookupRequest.setType(medium)
        lookupRequest.setThreePid(address)

        log.info("Got request from {} - Is recursive? {}", lookupRequest.getRequester(), lookupRequest.isRecursive())

        Optional<?> lookupOpt = strategy.find(lookupRequest)
        if (!lookupOpt.isPresent()) {
            log.info("No mapping was found, return empty JSON object")
            return JsonOutput.toJson([])
        }

        def lookup = lookupOpt.get()
        if (lookup['signatures'] == null) {
            log.info("lookup is not signed yet, we sign it")
            lookup['signatures'] = signMgr.signMessage(JsonOutput.toJson(lookup))
        }

        return JsonOutput.toJson(lookup)
    }

    @RequestMapping(value = "/_matrix/identity/api/v1/bulk_lookup", method = POST)
    String bulkLookup(HttpServletRequest request) {
        BulkLookupRequest lookupRequest = new BulkLookupRequest()
        setRequesterInfo(lookupRequest, request)
        log.info("Got request from {} - Is recursive? {}", lookupRequest.getRequester(), lookupRequest.isRecursive())

        ClientBulkLookupRequest input = (ClientBulkLookupRequest) json.parseText(request.getInputStream().getText())
        List<ThreePidMapping> mappings = new ArrayList<>()
        for (List<String> mappingRaw : input.getThreepids()) {
            ThreePidMapping mapping = new ThreePidMapping()
            mapping.setMedium(mappingRaw.get(0))
            mapping.setValue(mappingRaw.get(1))
            mappings.add(mapping)
        }
        lookupRequest.setMappings(mappings)

        ClientBulkLookupAnswer answer = new ClientBulkLookupAnswer()
        answer.addAll(strategy.find(lookupRequest))
        return JsonOutput.toJson(answer)
    }

}
