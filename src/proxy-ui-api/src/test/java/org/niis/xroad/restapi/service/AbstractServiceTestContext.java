/**
 * The MIT License
 * Copyright (c) 2019- Nordic Institute for Interoperability Solutions (NIIS)
 * Copyright (c) 2018 Estonian Information System Authority (RIA),
 * Nordic Institute for Interoperability Solutions (NIIS), Population Register Centre (VRK)
 * Copyright (c) 2015-2017 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.niis.xroad.restapi.service;

import ee.ria.xroad.common.conf.serverconf.model.ClientType;
import ee.ria.xroad.common.conf.serverconf.model.ServerConfType;
import ee.ria.xroad.common.identifier.ClientId;

import org.junit.Before;
import org.mockito.stubbing.Answer;
import org.niis.xroad.restapi.config.AbstractFacadeMockingTestContext;
import org.niis.xroad.restapi.repository.AnchorRepository;
import org.niis.xroad.restapi.repository.BackupRepository;
import org.niis.xroad.restapi.repository.ClientRepository;
import org.niis.xroad.restapi.repository.IdentifierRepository;
import org.niis.xroad.restapi.repository.LocalGroupRepository;
import org.niis.xroad.restapi.repository.ServerConfRepository;
import org.niis.xroad.restapi.util.TestUtils;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base for all service tests that need injected/mocked beans in the application context. All service
 * test classes inheriting this will have a common Spring Application Context therefore drastically reducing
 * the execution time of the service tests.
 *
 * TO DO: explain "you should not introduce mocks in inheriting classes"...
 *
 * Only repository layer (or anything below service layer) may be mocked
 */
public abstract class AbstractServiceTestContext extends AbstractFacadeMockingTestContext {
    @MockBean
    BackupRepository backupRepository;
    @MockBean
    ClientRepository clientRepository;
    @MockBean
    ServerConfRepository serverConfRepository;
    @MockBean
    AnchorRepository anchorRepository;
    @MockBean
    IdentifierRepository identifierRepository;
    @MockBean
    LocalGroupRepository localGroupRepository;
    @MockBean
    ServerConfType serverConfType;

    static final ClientId COMMON_OWNER_ID = TestUtils.getClientId("FI", "GOV", "M1", null);

    @Before
    public void setupCommonMocks() {
        ServerConfType sct = new ServerConfType();
        ClientType owner = new ClientType();
        owner.setIdentifier(COMMON_OWNER_ID);
        sct.setOwner(owner);
        sct.setServerCode("SS1");
        when(serverConfRepository.getServerConf()).thenReturn(sct);
        when(globalConfFacade.getMemberName(any())).thenAnswer((Answer<String>) invocation -> {
            Object[] args = invocation.getArguments();
            ClientId identifier = (ClientId) args[0];
            return identifier.getSubsystemCode() != null ? TestUtils.NAME_FOR + identifier.getSubsystemCode()
                    : TestUtils.NAME_FOR + "test-member";
        });
        when(clientRepository.getClient(any(ClientId.class))).thenAnswer((Answer<ClientType>) invocation -> {
            Object[] args = invocation.getArguments();
            ClientId identifier = (ClientId) args[0];
            ClientType clientType = new ClientType();
            clientType.setIdentifier(identifier);
            return clientType;
        });
    }
}