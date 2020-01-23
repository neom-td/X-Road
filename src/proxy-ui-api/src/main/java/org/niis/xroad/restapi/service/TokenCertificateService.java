/**
 * The MIT License
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

import ee.ria.xroad.common.CodedException;
import ee.ria.xroad.common.certificateprofile.CertificateProfileInfo;
import ee.ria.xroad.common.certificateprofile.DnFieldValue;
import ee.ria.xroad.common.certificateprofile.impl.SignCertificateProfileInfoParameters;
import ee.ria.xroad.common.identifier.ClientId;
import ee.ria.xroad.common.identifier.SecurityServerId;
import ee.ria.xroad.common.util.CertUtils;
import ee.ria.xroad.common.util.CryptoUtils;
import ee.ria.xroad.commonui.SignerProxy.GeneratedCertRequestInfo;
import ee.ria.xroad.signer.protocol.dto.CertRequestInfo;
import ee.ria.xroad.signer.protocol.dto.CertificateInfo;
import ee.ria.xroad.signer.protocol.dto.KeyInfo;
import ee.ria.xroad.signer.protocol.dto.KeyUsageInfo;
import ee.ria.xroad.signer.protocol.dto.TokenInfo;
import ee.ria.xroad.signer.protocol.dto.TokenInfoAndKeyId;
import ee.ria.xroad.signer.protocol.message.CertificateRequestFormat;

import lombok.extern.slf4j.Slf4j;
import org.niis.xroad.restapi.exceptions.DeviationAwareRuntimeException;
import org.niis.xroad.restapi.exceptions.ErrorDeviation;
import org.niis.xroad.restapi.facade.GlobalConfFacade;
import org.niis.xroad.restapi.facade.SignerProxyFacade;
import org.niis.xroad.restapi.repository.ClientRepository;
import org.niis.xroad.restapi.util.FormatUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ee.ria.xroad.common.ErrorCodes.SIGNER_X;
import static ee.ria.xroad.common.ErrorCodes.X_CERT_EXISTS;
import static ee.ria.xroad.common.ErrorCodes.X_CERT_NOT_FOUND;
import static ee.ria.xroad.common.ErrorCodes.X_CSR_NOT_FOUND;
import static ee.ria.xroad.common.ErrorCodes.X_INCORRECT_CERTIFICATE;
import static ee.ria.xroad.common.ErrorCodes.X_WRONG_CERT_USAGE;
import static org.niis.xroad.restapi.service.KeyService.isCausedByKeyNotFound;
import static org.niis.xroad.restapi.service.SecurityHelper.verifyAuthority;

/**
 * token certificate service
 */
@Slf4j
@Service
@Transactional
@PreAuthorize("isAuthenticated()")
public class TokenCertificateService {

    private static final String DUMMY_MEMBER = "dummy";
    private static final String NOT_FOUND = "not found";
    private static final String IMPORT_AUTH_CERT = "IMPORT_AUTH_CERT";
    private static final String IMPORT_SIGN_CERT = "IMPORT_SIGN_CERT";

    private final GlobalConfService globalConfService;
    private final GlobalConfFacade globalConfFacade;
    private final SignerProxyFacade signerProxyFacade;
    private final ClientRepository clientRepository;
    private final ManagementRequestSenderService managementRequestSenderService;
    private final ServerConfService serverConfService;
    private final ClientService clientService;
    private final CertificateAuthorityService certificateAuthorityService;
    private final KeyService keyService;
    private final DnFieldHelper dnFieldHelper;
    private final PossibleActionsRuleEngine possibleActionsRuleEngine;
    private final TokenService tokenService;

    @Autowired
    public TokenCertificateService(SignerProxyFacade signerProxyFacade, ClientService clientService,
            CertificateAuthorityService certificateAuthorityService, KeyService keyService, DnFieldHelper dnFieldHelper,
            GlobalConfService globalConfService, GlobalConfFacade globalConfFacade, ClientRepository clientRepository,
            ManagementRequestSenderService managementRequestSenderService, ServerConfService serverConfService,
            PossibleActionsRuleEngine possibleActionsRuleEngine, TokenService tokenService) {
        this.signerProxyFacade = signerProxyFacade;
        this.clientService = clientService;
        this.certificateAuthorityService = certificateAuthorityService;
        this.keyService = keyService;
        this.dnFieldHelper = dnFieldHelper;
        this.globalConfService = globalConfService;
        this.globalConfFacade = globalConfFacade;
        this.clientRepository = clientRepository;
        this.managementRequestSenderService = managementRequestSenderService;
        this.serverConfService = serverConfService;
        this.tokenService = tokenService;
        this.possibleActionsRuleEngine = possibleActionsRuleEngine;
    }

    /**
     * Create a CSR
     * @param keyId
     * @param memberId
     * @param keyUsage
     * @param caName
     * @param subjectFieldValues user-submitted parameters for subject DN
     * @param format
     * @return GeneratedCertRequestInfo containing details and bytes of the cert request
     * @throws CertificateAuthorityNotFoundException if ca authority with name {@code caName} does not exist
     * @throws ClientNotFoundException if client with {@code memberId} id was not found
     * @throws KeyNotFoundException if key with {@code keyId} was not found
     * @throws WrongKeyUsageException if keyUsage param did not match the key's usage type
     * @throws DnFieldHelper.InvalidDnParameterException if required dn parameters were missing, or if there
     * were some extra parameters
     * @throws ActionNotPossibleException if generate csr was not possible for this key
     */
    public GeneratedCertRequestInfo generateCertRequest(String keyId, ClientId memberId, KeyUsageInfo keyUsage,
            String caName, Map<String, String> subjectFieldValues, CertificateRequestFormat format)
            throws CertificateAuthorityNotFoundException, ClientNotFoundException,
            WrongKeyUsageException,
            KeyNotFoundException,
            DnFieldHelper.InvalidDnParameterException, ActionNotPossibleException {

        // CertificateProfileInstantiationException

        // validate key and memberId existence
        TokenInfo tokenInfo = tokenService.getTokenForKeyId(keyId);
        KeyInfo key = keyService.getKey(tokenInfo, keyId);

        if (keyUsage == KeyUsageInfo.SIGNING) {
            // validate that the member exists or has a subsystem on this server
            if (!clientService.getLocalClientMemberIds().contains(memberId)) {
                throw new ClientNotFoundException("client with id " + memberId + ", or subsystem for it, " + NOT_FOUND);
            }
        }

        // check that keyUsage is allowed
        if (key.getUsage() != null) {
            if (key.getUsage() != keyUsage) {
                throw new WrongKeyUsageException();
            }
        }

        // validate that generate csr is possible
        if (keyUsage == KeyUsageInfo.SIGNING) {
            possibleActionsRuleEngine.requirePossibleKeyAction(PossibleActionEnum.GENERATE_SIGN_CSR,
                    tokenInfo, key);
        } else {
            possibleActionsRuleEngine.requirePossibleKeyAction(PossibleActionEnum.GENERATE_AUTH_CSR,
                    tokenInfo, key);
        }

        CertificateProfileInfo profile = null;
        try {
            profile = certificateAuthorityService.getCertificateProfile(caName, keyUsage, memberId);
        } catch (CertificateProfileInstantiationException e) {
            throw new DeviationAwareRuntimeException(e, e.getErrorDeviation());
        }

        List<DnFieldValue> dnFieldValues = dnFieldHelper.processDnParameters(profile, subjectFieldValues);

        String subjectName = dnFieldHelper.createSubjectName(dnFieldValues);

        try {
            return signerProxyFacade.generateCertRequest(keyId, memberId,
                    keyUsage, subjectName, format);
        } catch (CodedException e) {
            throw e;
        } catch (Exception e) {
            throw new DeviationAwareRuntimeException(e);
        }
    }

    /**
     * Regenerate a csr. Regenerate is used by download -endpoint.
     * Regenerate will find an existing csr from TokenManager, and
     * regenerate a new csr binary for it. TokenManager itself, and the csr
     * info stored inside it, will be unchanged.
     *
     * Permissions and possible actions use the values for generate csr,
     * there are no separate values for this operation.
     *
     * @param keyId
     * @param csrId
     * @param format
     * @return GeneratedCertRequestInfo containing details and bytes of the cert request
     * @throws KeyNotFoundException if key with keyId was not found
     * @throws CsrNotFoundException if csr with csrId was not found
     * @throws ActionNotPossibleException if regenerate was not possible
     */
    public GeneratedCertRequestInfo regenerateCertRequest(String keyId, String csrId, CertificateRequestFormat format)
            throws KeyNotFoundException, CsrNotFoundException, ActionNotPossibleException {

        // validate key and memberId existence
        TokenInfo tokenInfo = tokenService.getTokenForKeyId(keyId);
        KeyInfo keyInfo = keyService.getKey(tokenInfo, keyId);
        getCsr(keyInfo, csrId);

        // currently regenerate is part of "generate csr" or
        // "combo generate key + csr" operations in the web application.
        // There are no separate permissions or possible action rules for
        // downloading the csr.
        // Downloading a csr means actually re-generating it, so the
        // possible action rules for it would be very close, if not identical,
        // to generate csr.

        // check usage type specific auth in service, since controller does not know usage type
        if (keyInfo.isForSigning()) {
            verifyAuthority("GENERATE_SIGN_CERT_REQ");
        } else {
            verifyAuthority("GENERATE_AUTH_CERT_REQ");
        }

        // validate that regenerate csr is a possible action
        if (keyInfo.isForSigning()) {
            possibleActionsRuleEngine.requirePossibleKeyAction(PossibleActionEnum.GENERATE_SIGN_CSR,
                    tokenInfo, keyInfo);
        } else {
            possibleActionsRuleEngine.requirePossibleKeyAction(PossibleActionEnum.GENERATE_AUTH_CSR,
                    tokenInfo, keyInfo);
        }

        try {
            return signerProxyFacade.regenerateCertRequest(csrId, format);
        } catch (CodedException e) {
            throw e;
        } catch (Exception e) {
            throw new DeviationAwareRuntimeException(e);
        }
    }


    private static String signerFaultCode(String detail) {
        return SIGNER_X + "." + detail;
    }

    /**
     * Find an existing cert from a token by it's hash
     * @param hash cert hash of an existing cert. Will be transformed to lowercase
     * @return
     * @throws CertificateNotFoundException
     */
    public CertificateInfo getCertificateInfo(String hash) throws CertificateNotFoundException {
        CertificateInfo certificateInfo = null;
        try {
            certificateInfo = signerProxyFacade.getCertForHash(hash);
        } catch (CodedException e) {
            if (isCausedByCertNotFound(e)) {
                throw new CertificateNotFoundException("Certificate with hash " + hash + " " + NOT_FOUND);
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("error getting certificate", e);
        }
        return certificateInfo;
    }

    /**
     * Find an existing cert from a token (e.g. HSM) by cert hash and import it to keyconf.xml. This enables the cert
     * to be used for signing messages.
     * @param hash cert hash of an existing cert
     * @return CertificateType
     * @throws CertificateNotFoundException
     * @throws InvalidCertificateException other general import failure
     * @throws GlobalConfService.GlobalConfOutdatedException
     * @throws KeyNotFoundException
     * @throws CertificateAlreadyExistsException
     * @throws WrongCertificateUsageException
     * @throws ClientNotFoundException
     * @throws CsrNotFoundException
     * @throws AuthCertificateNotSupportedException if trying to import an auth cert from a token
     * @throws ActionNotPossibleException if import was not possible due to cert/key/token states
     */
    public CertificateInfo importCertificateFromToken(String hash) throws CertificateNotFoundException,
            InvalidCertificateException, GlobalConfService.GlobalConfOutdatedException, KeyNotFoundException,
            CertificateAlreadyExistsException, WrongCertificateUsageException, ClientNotFoundException,
            CsrNotFoundException, AuthCertificateNotSupportedException, ActionNotPossibleException {
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        EnumSet<PossibleActionEnum> possibleActions =
                getPossibleActionsForCertificateInternal(hash, certificateInfo, null, null);
        possibleActionsRuleEngine.requirePossibleAction(
                PossibleActionEnum.IMPORT_FROM_TOKEN, possibleActions);
        return importCertificate(certificateInfo.getCertificateBytes(), true);
    }

    /**
     * Import a cert that is found from a token by it's bytes
     * @param certificateBytes
     * @param isFromToken whether the cert was read from a token or not
     * @return CertificateType
     * @throws GlobalConfService.GlobalConfOutdatedException
     * @throws KeyNotFoundException
     * @throws InvalidCertificateException other general import failure
     * @throws CertificateAlreadyExistsException
     * @throws WrongCertificateUsageException
     * @throws AuthCertificateNotSupportedException if trying to import an auth cert from a token
     */
    private CertificateInfo importCertificate(byte[] certificateBytes, boolean isFromToken)
            throws GlobalConfService.GlobalConfOutdatedException, KeyNotFoundException, InvalidCertificateException,
            CertificateAlreadyExistsException, WrongCertificateUsageException, CsrNotFoundException,
            AuthCertificateNotSupportedException, ClientNotFoundException {
        globalConfService.verifyGlobalConfValidity();
        X509Certificate x509Certificate = null;
        CertificateInfo certificateInfo = null;
        try {
            x509Certificate = CryptoUtils.readCertificate(certificateBytes);
        } catch (Exception e) {
            throw new InvalidCertificateException("cannot convert bytes to certificate", e);
        }
        try {
            String certificateState;
            ClientId clientId = null;
            boolean isAuthCert = CertUtils.isAuthCert(x509Certificate);
            if (isAuthCert) {
                verifyAuthority(IMPORT_AUTH_CERT);
                if (isFromToken) {
                    throw new AuthCertificateNotSupportedException("auth cert cannot be imported from a token");
                }
                certificateState = CertificateInfo.STATUS_SAVED;
            } else {
                verifyAuthority(IMPORT_SIGN_CERT);
                String xroadInstance = globalConfFacade.getInstanceIdentifier();
                clientId = getClientIdForSigningCert(xroadInstance, x509Certificate);
                boolean clientExists = clientRepository.clientExists(clientId, true);
                if (!clientExists) {
                    throw new ClientNotFoundException("client " + clientId.toShortString() + " " + NOT_FOUND,
                            FormatUtils.xRoadIdToEncodedId(clientId));
                }
                certificateState = CertificateInfo.STATUS_REGISTERED;
            }
            byte[] certBytes = x509Certificate.getEncoded();
            signerProxyFacade.importCert(certBytes, certificateState, clientId);
            String hash = CryptoUtils.calculateCertHexHash(certBytes);
            certificateInfo = getCertificateInfo(hash);
        } catch (ClientNotFoundException | AccessDeniedException | AuthCertificateNotSupportedException e) {
            throw e;
        } catch (CodedException e) {
            translateCodedExceptions(e);
        } catch (Exception e) {
            // something went really wrong
            throw new RuntimeException("error importing certificate", e);
        }
        return certificateInfo;
    }

    /**
     * Activates certificate
     * @param hash
     * @throws CertificateNotFoundException
     * @throws AccessDeniedException
     */
    public void activateCertificate(String hash) throws CertificateNotFoundException,
            AccessDeniedException, InvalidCertificateException {
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        try {
            verifyActivateDisableAuthority(certificateInfo.getCertificateBytes());
        } catch (InvalidCertificateException e) {
            throw e;
        }

        try {
            signerProxyFacade.activateCert(certificateInfo.getId());
        } catch (CodedException e) {
            if (isCausedByCertNotFound(e)) {
                throw new CertificateNotFoundException("Certificate with id " + certificateInfo.getId() + " "
                        + NOT_FOUND);
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("certificate activation failed", e);
        }
    }

    /**
     * Deactivates certificate
     * @param hash
     * @throws CertificateNotFoundException
     */
    public void deactivateCertificate(String hash) throws CertificateNotFoundException, AccessDeniedException,
            InvalidCertificateException {
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        try {
            verifyActivateDisableAuthority(certificateInfo.getCertificateBytes());
        } catch (InvalidCertificateException e) {
            throw e;
        }

        try {
            signerProxyFacade.deactivateCert(certificateInfo.getId());
        } catch (CodedException e) {
            if (isCausedByCertNotFound(e)) {
                throw new CertificateNotFoundException("Certificate with id " + certificateInfo.getId() + " "
                        + NOT_FOUND);
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("certificate deactivation failed", e);
        }
    }

    /**
     * Import a cert from given bytes. If importing an existing cert from a token use
     * {@link #importCertificateFromToken(String hash)}
     * @param certificateBytes
     * @return CertificateType
     * @throws GlobalConfService.GlobalConfOutdatedException
     * @throws ClientNotFoundException
     * @throws KeyNotFoundException
     * @throws InvalidCertificateException other general import failure
     * @throws CertificateAlreadyExistsException
     * @throws WrongCertificateUsageException
     * @throws AuthCertificateNotSupportedException if trying to import an auth cert from a token
     */
    public CertificateInfo importCertificate(byte[] certificateBytes) throws InvalidCertificateException,
            GlobalConfService.GlobalConfOutdatedException, KeyNotFoundException, CertificateAlreadyExistsException,
            WrongCertificateUsageException, ClientNotFoundException, CsrNotFoundException,
            AuthCertificateNotSupportedException {
        return importCertificate(certificateBytes, false);
    }

    /**
     * Check user authority to the given certificate
     * @param certificateBytes
     * @throws InvalidCertificateException
     * @throws AccessDeniedException
     */
    public void verifyActivateDisableAuthority(byte[] certificateBytes) throws InvalidCertificateException,
            AccessDeniedException {
        X509Certificate x509Certificate = null;
        try {
            x509Certificate = CryptoUtils.readCertificate(certificateBytes);
        } catch (Exception e) {
            throw new InvalidCertificateException("cannot convert bytes to certificate", e);
        }

        try {
            boolean isAuthCert = CertUtils.isAuthCert(x509Certificate);
            if (isAuthCert) {
                verifyAuthority("ACTIVATE_DISABLE_AUTH_CERT");
            } else {
                verifyAuthority("ACTIVATE_DISABLE_SIGN_CERT");
            }
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("error in checking authority to the certificate", e);
        }
    }

    /**
     * Returns the given certificate owner's client ID.
     * @param instanceIdentifier instance identifier of the owner
     * @param cert the certificate
     * @return certificate owner's client ID
     * @throws InvalidCertificateException if any errors occur
     */
    private ClientId getClientIdForSigningCert(String instanceIdentifier, X509Certificate cert)
            throws InvalidCertificateException {
        ClientId dummyClientId = ClientId.create(instanceIdentifier, DUMMY_MEMBER, DUMMY_MEMBER);
        SignCertificateProfileInfoParameters signCertificateProfileInfoParameters =
                new SignCertificateProfileInfoParameters(dummyClientId, DUMMY_MEMBER);
        ClientId certificateSubject;
        try {
            certificateSubject = globalConfFacade.getSubjectName(signCertificateProfileInfoParameters, cert);
        } catch (Exception e) {
            throw new InvalidCertificateException("Cannot read member identifier from signing certificate", e);
        }
        return certificateSubject;
    }

    /**
     * Verify if action can be performed on cert
     * @param action
     * @param certificateInfo
     * @param hash
     * @throws CertificateNotFoundException
     * @throws KeyNotFoundException
     * @throws ActionNotPossibleException
     */
    private void verifyCertAction(PossibleActionEnum action, CertificateInfo certificateInfo, String hash) throws
            CertificateNotFoundException, KeyNotFoundException, ActionNotPossibleException {
        TokenInfoAndKeyId tokenInfoAndKeyId = tokenService.getTokenAndKeyIdForCertificateHash(hash);
        TokenInfo tokenInfo = tokenInfoAndKeyId.getTokenInfo();
        KeyInfo keyInfo = tokenInfoAndKeyId.getKeyInfo();
        possibleActionsRuleEngine.requirePossibleCertificateAction(action, tokenInfo, keyInfo, certificateInfo);
    }

    /**
     * Send the authentication certificate registration request to central server
     * @param hash certificate hash
     * @param securityServerAddress IP address or DNS name of the security server
     * @throws CertificateNotFoundException
     * @throws GlobalConfService.GlobalConfOutdatedException
     */
    public void registerAuthCert(String hash, String securityServerAddress) throws CertificateNotFoundException,
            GlobalConfService.GlobalConfOutdatedException, InvalidCertificateException,
            SignCertificateNotSupportedException, KeyNotFoundException, ActionNotPossibleException {
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        verifyAuthCert(certificateInfo);
        verifyCertAction(PossibleActionEnum.REGISTER, certificateInfo, hash);
        SecurityServerId securityServerId = serverConfService.getSecurityServerId();
        try {
            managementRequestSenderService.sendAuthCertRegisterRequest(securityServerAddress,
                    certificateInfo.getCertificateBytes());
            signerProxyFacade.setCertStatus(certificateInfo.getId(), CertificateInfo.STATUS_REGINPROG);
        } catch (GlobalConfService.GlobalConfOutdatedException | CodedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not register auth cert", e);
        }
    }

    /**
     * Send the authentication certificate deletion request to central server.
     * Also sets cert status to STATUS_DELINPROG
     * @param hash certificate hash
     * @throws CertificateNotFoundException
     * @throws GlobalConfService.GlobalConfOutdatedException
     */
    public void unregisterAuthCert(String hash) throws CertificateNotFoundException,
            GlobalConfService.GlobalConfOutdatedException, InvalidCertificateException,
            SignCertificateNotSupportedException, KeyNotFoundException, ActionNotPossibleException {
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        verifyAuthCert(certificateInfo);
        verifyCertAction(PossibleActionEnum.UNREGISTER, certificateInfo, hash);
        try {
            managementRequestSenderService.sendAuthCertDeletionRequest(
                    certificateInfo.getCertificateBytes());
            signerProxyFacade.setCertStatus(certificateInfo.getId(), CertificateInfo.STATUS_DELINPROG);
        } catch (GlobalConfService.GlobalConfOutdatedException | CodedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not unregister auth cert", e);
        }
    }

    private void verifyAuthCert(CertificateInfo certificateInfo)
            throws SignCertificateNotSupportedException, InvalidCertificateException {
        boolean isAuthCert;
        X509Certificate certificate = null;
        try {
            certificate = CryptoUtils.readCertificate(certificateInfo.getCertificateBytes());
            isAuthCert = CertUtils.isAuthCert(certificate);
            if (!isAuthCert) {
                throw new SignCertificateNotSupportedException("not an auth cert");
            }
        } catch (SignCertificateNotSupportedException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCertificateException("invalid certificate", e);
        }
    }

    /**
     * Helper to translate caught {@link CodedException CodedExceptions}
     * @param e
     * @throws CertificateAlreadyExistsException
     * @throws InvalidCertificateException
     * @throws WrongCertificateUsageException
     * @throws CsrNotFoundException
     * @throws KeyNotFoundException
     */
    private void translateCodedExceptions(CodedException e) throws CertificateAlreadyExistsException,
            InvalidCertificateException, WrongCertificateUsageException, CsrNotFoundException, KeyNotFoundException {
        if (isCausedByDuplicateCertificate(e)) {
            throw new CertificateAlreadyExistsException(e);
        } else if (isCausedByIncorrectCertificate(e)) {
            throw new InvalidCertificateException(e);
        } else if (isCausedByCertificateWrongUsage(e)) {
            throw new WrongCertificateUsageException(e);
        } else if (isCausedByCsrNotFound(e)) {
            throw new CsrNotFoundException(e);
        } else if (isCausedByKeyNotFound(e)) {
            throw new KeyNotFoundException(e);
        } else {
            throw e;
        }
    }

    static boolean isCausedByDuplicateCertificate(CodedException e) {
        return DUPLICATE_CERT_FAULT_CODE.equals(e.getFaultCode());
    }

    static boolean isCausedByIncorrectCertificate(CodedException e) {
        return INCORRECT_CERT_FAULT_CODE.equals(e.getFaultCode());
    }

    static boolean isCausedByCertificateWrongUsage(CodedException e) {
        return CERT_WRONG_USAGE_FAULT_CODE.equals(e.getFaultCode());
    }

    static boolean isCausedByCsrNotFound(CodedException e) {
        return CSR_NOT_FOUND_FAULT_CODE.equals(e.getFaultCode());
    }

    static boolean isCausedByCertNotFound(CodedException e) {
        return CERT_NOT_FOUND_FAULT_CODE.equals(e.getFaultCode());
    }

    static final String DUPLICATE_CERT_FAULT_CODE = signerFaultCode(X_CERT_EXISTS);
    static final String INCORRECT_CERT_FAULT_CODE = signerFaultCode(X_INCORRECT_CERTIFICATE);
    static final String CERT_WRONG_USAGE_FAULT_CODE = signerFaultCode(X_WRONG_CERT_USAGE);
    static final String CSR_NOT_FOUND_FAULT_CODE = signerFaultCode(X_CSR_NOT_FOUND);
    static final String CERT_NOT_FOUND_FAULT_CODE = signerFaultCode(X_CERT_NOT_FOUND);

    /**
     * Return possible actions for one cert
     * @param hash
     * @return
     * @throws CertificateNotFoundException
     */
    public EnumSet<PossibleActionEnum> getPossibleActionsForCertificate(String hash)
            throws CertificateNotFoundException {
        return getPossibleActionsForCertificateInternal(hash, null, null, null);
    }

    /**
     * Return possible actions for one csr
     * Key not found exceptions are wrapped as RuntimeExceptions
     * since them happening is considered to be internal error.
     * @throws CertificateNotFoundException
     */
    public EnumSet<PossibleActionEnum> getPossibleActionsForCsr(
            String csrId) throws CsrNotFoundException {

        TokenInfoAndKeyId tokenInfoAndKeyId = null;
        try {
            tokenInfoAndKeyId = tokenService.getTokenAndKeyIdForCertificateRequestId(csrId);
        } catch (KeyNotFoundException e) {
            throw new RuntimeException("internal error", e);
        }
        TokenInfo tokenInfo = tokenInfoAndKeyId.getTokenInfo();
        KeyInfo keyInfo = tokenInfoAndKeyId.getKeyInfo();
        CertRequestInfo certRequestInfo = getCsr(keyInfo, csrId);

        EnumSet<PossibleActionEnum> possibleActions = possibleActionsRuleEngine.
                getPossibleCsrActions(tokenInfo);
        return possibleActions;
    }

    /**
     * Helper method which finds possible actions for certificate with given hash.
     * Either uses given CertificateInfo, KeyInfo and TokenInfo objects, or looks
     * them up based on cert hash if not given.
     * If TokenInfo needs to be loaded, ignores KeyInfo parameter and uses loaded TokenInfo
     * instead to determine correct KeyInfo.
     * Key not found exceptions are wrapped as RuntimeExceptions
     * since them happening is considered to be internal error.
     * @throws CertificateNotFoundException
     */
    private EnumSet<PossibleActionEnum> getPossibleActionsForCertificateInternal(
            String hash,
            CertificateInfo certificateInfo,
            KeyInfo keyInfo,
            TokenInfo tokenInfo) throws CertificateNotFoundException {

        if (certificateInfo == null) {
            certificateInfo = getCertificateInfo(hash);
        }

        try {
            if (tokenInfo == null) {
                TokenInfoAndKeyId tokenInfoAndKeyId = tokenService.getTokenAndKeyIdForCertificateHash(hash);
                tokenInfo = tokenInfoAndKeyId.getTokenInfo();
                keyInfo = tokenInfoAndKeyId.getKeyInfo();
            }
            if (keyInfo == null) {
                String keyId = getKeyIdForCertificateHash(hash);
                keyInfo = keyService.getKey(keyId);
            }
        } catch (KeyNotFoundException e) {
            throw new RuntimeException("internal error", e);
        }

        EnumSet<PossibleActionEnum> possibleActions = possibleActionsRuleEngine.
                getPossibleCertificateActions(tokenInfo, keyInfo, certificateInfo);
        return possibleActions;
    }

    /**
     * Delete certificate with given hash
     * @param hash
     * @throws CertificateNotFoundException if certificate with given hash was not found
     * @throws KeyNotFoundException if for some reason the key linked to the cert could not
     * be loaded (should not be possible)
     * @throws ActionNotPossibleException if delete was not possible due to cert/key/token states
     */
    public void deleteCertificate(String hash) throws CertificateNotFoundException, KeyNotFoundException,
            ActionNotPossibleException {
        hash = hash.toLowerCase();
        CertificateInfo certificateInfo = getCertificateInfo(hash);
        String keyId = getKeyIdForCertificateHash(hash);
        KeyInfo keyInfo = keyService.getKey(keyId);
        EnumSet<PossibleActionEnum> possibleActions =
                getPossibleActionsForCertificateInternal(hash, certificateInfo, keyInfo, null);
        possibleActionsRuleEngine.requirePossibleAction(
                PossibleActionEnum.DELETE, possibleActions);

        if (keyInfo.isForSigning()) {
            verifyAuthority("DELETE_SIGN_CERT");
        } else {
            verifyAuthority("DELETE_AUTH_CERT");
        }
        try {
            signerProxyFacade.deleteCert(certificateInfo.getId());
        } catch (CodedException e) {
            if (isCausedByCertNotFound(e)) {
                throw new CertificateNotFoundException(e, new ErrorDeviation(
                        CertificateNotFoundException.ERROR_CERTIFICATE_NOT_FOUND_WITH_ID,
                        certificateInfo.getId()));
            } else {
                throw e;
            }
        } catch (Exception other) {
            throw new RuntimeException("deleting a csr failed", other);
        }
    }

    /**
     * Return key id for a key containing a cert with given hash
     * @throws CertificateNotFoundException if no match found
     */
    public String getKeyIdForCertificateHash(String hash) throws CertificateNotFoundException {
        try {
            return signerProxyFacade.getKeyIdForCertHash(hash);
        } catch (CodedException e) {
            if (isCausedByCertNotFound(e)) {
                throw new CertificateNotFoundException("Certificate with hash " + hash + " not found");
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("error getting certificate", e);
        }
    }

    /**
     * Deletes one csr
     * @param csrId
     * @throws KeyNotFoundException if for some reason the key linked to the csr could not
     * be loaded (should not be possible)
     * @throws CsrNotFoundException if csr with csrId was not found
     * @throws ActionNotPossibleException if delete was not possible due to csr/key/token states
     */
    public void deleteCsr(String csrId) throws KeyNotFoundException, CsrNotFoundException,
            ActionNotPossibleException {

        TokenInfoAndKeyId tokenInfoAndKeyId = tokenService.getTokenAndKeyIdForCertificateRequestId(csrId);
        TokenInfo tokenInfo = tokenInfoAndKeyId.getTokenInfo();
        KeyInfo keyInfo = tokenInfoAndKeyId.getKeyInfo();
        CertRequestInfo certRequestInfo = getCsr(keyInfo, csrId);

        if (keyInfo.isForSigning()) {
            verifyAuthority("DELETE_SIGN_CERT");
        } else {
            verifyAuthority("DELETE_AUTH_CERT");
        }

        // check that delete is possible
        possibleActionsRuleEngine.requirePossibleCsrAction(
                PossibleActionEnum.DELETE, tokenInfo, keyInfo, certRequestInfo);

        try {
            signerProxyFacade.deleteCertRequest(csrId);
        } catch (CodedException e) {
            if (isCausedByCsrNotFound(e)) {
                throw new CsrNotFoundException(e);
            } else {
                throw e;
            }
        } catch (Exception other) {
            throw new RuntimeException("deleting a csr failed", other);
        }
    }

    /**
     * Finds csr with matching id from KeyInfo, or throws {@link CsrNotFoundException}
     * @throws CsrNotFoundException
     */
    private CertRequestInfo getCsr(KeyInfo keyInfo, String csrId) throws CsrNotFoundException {
        Optional<CertRequestInfo> csr = keyInfo.getCertRequests().stream()
                .filter(csrInfo -> csrInfo.getId().equals(csrId))
                .findFirst();
        if (!csr.isPresent()) {
            throw new CsrNotFoundException("csr with id " + csrId + " " + NOT_FOUND);
        }
        return csr.get();
    }

    /**
     * General error that happens when importing a cert. Usually a wrong file type
     */
    public static class InvalidCertificateException extends ServiceException {
        public static final String INVALID_CERT = "invalid_cert";

        public InvalidCertificateException(Throwable t) {
            super(t, new ErrorDeviation(INVALID_CERT));
        }

        public InvalidCertificateException(String msg, Throwable t) {
            super(msg, t, new ErrorDeviation(INVALID_CERT));
        }
    }

    /**
     * Cert usage info is wrong (e.g. cert is both auth and sign or neither)
     */
    public static class WrongCertificateUsageException extends ServiceException {
        public static final String ERROR_CERTIFICATE_WRONG_USAGE = "cert_wrong_usage";

        public WrongCertificateUsageException(Throwable t) {
            super(t, new ErrorDeviation(ERROR_CERTIFICATE_WRONG_USAGE));
        }
    }

    /**
     * Probably a rare case of when importing an auth cert from an HSM
     */
    public static class AuthCertificateNotSupportedException extends ServiceException {
        public static final String AUTH_CERT_NOT_SUPPORTED = "auth_cert_not_supported";

        public AuthCertificateNotSupportedException(String msg) {
            super(msg, new ErrorDeviation(AUTH_CERT_NOT_SUPPORTED));
        }
    }

    /**
     * When trying to register a sign cert
     */
    public static class SignCertificateNotSupportedException extends ServiceException {
        public static final String SIGN_CERT_NOT_SUPPORTED = "sign_cert_not_supported";

        public SignCertificateNotSupportedException(String msg) {
            super(msg, new ErrorDeviation(SIGN_CERT_NOT_SUPPORTED));
        }
    }
}
