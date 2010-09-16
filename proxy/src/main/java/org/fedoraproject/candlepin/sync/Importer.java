/**
 * Copyright (c) 2009 Red Hat, Inc.
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
package org.fedoraproject.candlepin.sync;

import org.fedoraproject.candlepin.audit.EventSink;
import org.fedoraproject.candlepin.config.Config;
import org.fedoraproject.candlepin.controller.PoolManager;
import org.fedoraproject.candlepin.model.CertificateSerialCurator;
import org.fedoraproject.candlepin.model.ConsumerType;
import org.fedoraproject.candlepin.model.ConsumerTypeCurator;
import org.fedoraproject.candlepin.model.ContentCurator;
import org.fedoraproject.candlepin.model.ExporterMetadata;
import org.fedoraproject.candlepin.model.ExporterMetadataCurator;
import org.fedoraproject.candlepin.model.Owner;
import org.fedoraproject.candlepin.model.OwnerCurator;
import org.fedoraproject.candlepin.model.Product;
import org.fedoraproject.candlepin.model.ProductCurator;
import org.fedoraproject.candlepin.model.RulesCurator;
import org.fedoraproject.candlepin.model.Subscription;
import org.fedoraproject.candlepin.model.SubscriptionCurator;
import org.fedoraproject.candlepin.pki.PKIUtility;

import com.google.inject.Inject;
import com.wideplay.warp.persist.Transactional;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importer
 */
public class Importer {
    private static Logger log = Logger.getLogger(Importer.class);
    
    /**
     * 
     * files we use to perform import
     */
    enum ImportFile {
        META("meta.json"),
        CONSUMER_TYPE("consumer_types"),
        CONSUMER("consumer.json"),
        ENTITLEMENTS("entitlements"),
        ENTITLEMENT_CERTIFICATES("entitlement_certificates"),
        PRODUCTS("products"),
        RULES("rules");
        
        private String fileName;
        ImportFile(String fileName) {
            this.fileName = fileName;
        }
        
        public String fileName() {
            return fileName;
        }
        
    }

    private ConsumerTypeCurator consumerTypeCurator;
    private ProductCurator productCurator;
    private ObjectMapper mapper;
    private RulesCurator rulesCurator;
    private OwnerCurator ownerCurator;
    private ContentCurator contentCurator;
    private SubscriptionCurator subCurator;
    private PoolManager poolManager;
    private PKIUtility pki;
    private Config config;
    private ExporterMetadataCurator expMetaCurator;
    private CertificateSerialCurator csCurator;
    private EventSink sink;
    
    @Inject
    public Importer(ConsumerTypeCurator consumerTypeCurator, ProductCurator productCurator, 
        RulesCurator rulesCurator, OwnerCurator ownerCurator,
        ContentCurator contentCurator, SubscriptionCurator subCurator, PoolManager pm, 
        PKIUtility pki, Config config, ExporterMetadataCurator emc,
        CertificateSerialCurator csc, EventSink sink) {
        this.consumerTypeCurator = consumerTypeCurator;
        this.productCurator = productCurator;
        this.rulesCurator = rulesCurator;
        this.ownerCurator = ownerCurator;
        this.contentCurator = contentCurator;
        this.subCurator = subCurator;
        this.poolManager = pm;
        this.mapper = SyncUtils.getObjectMapper();
        this.pki = pki;
        this.config = config;
        this.expMetaCurator = emc;
        this.csCurator = csc;
        this.sink = sink;
    }

    /**
     * Check to make sure the meta data is newer than the imported data.
     * @param type ExporterMetadata.TYPE_PER_USER or TYPE_SYSTEM
     * @param owner Owner in the case of PER_USER
     * @param meta meta.json file
     * @throws IOException thrown if there's a problem reading the file
     * @throws ImporterException thrown if the metadata is invalid.
     */
    public void validateMetadata(String type, Owner owner, File meta)
        throws IOException, ImporterException {
        Meta m = mapper.readValue(meta, Meta.class);
        if (type == null) {
            throw new ImporterException("Wrong metadata type");
        }

        ExporterMetadata lastrun = null;
        if (ExporterMetadata.TYPE_SYSTEM.equals(type)) {
            lastrun = expMetaCurator.lookupByType(type);
        }
        else if (ExporterMetadata.TYPE_PER_USER.equals(type)) {
            if (owner == null) {
                throw new ImporterException("invalid owner");
            }
            lastrun = expMetaCurator.lookupByTypeAndOwner(type, owner);
        }

        if (lastrun == null) {
            // this is our first import, let's create a new entry
            lastrun = new ExporterMetadata(type, m.getCreated(), owner);
            lastrun = expMetaCurator.create(lastrun);
        }
        else {
            if (lastrun.getExported().compareTo(m.getCreated()) > 0) {
                throw new ImporterException("import is older than existing data");
            }
            else {
                lastrun.setExported(new Date());
            }
        }
    }

    public void loadExport(Owner owner, File exportFile) throws ImporterException {
        File tmpDir = null;
        InputStream exportStream = null;
        try {
            tmpDir = new SyncUtils(config).makeTempDir("import");
            
            extractArchive(tmpDir, exportFile);
            
            exportStream = new FileInputStream(new File(tmpDir, "consumer_export.zip"));
            boolean verifiedSignature = pki.verifySHA256WithRSAHashWithUpstreamCACert(
                exportStream,
                loadSignature(new File(tmpDir, "signature")));
            
            if (!verifiedSignature) {
                throw new ImporterException("failed import file hash check.");
            }
            
            File exportDir 
                = extractArchive(tmpDir, new File(tmpDir, "consumer_export.zip"));
            
            Map<String, File> importFiles = new HashMap<String, File>();
            for (File file : exportDir.listFiles()) {
                importFiles.put(file.getName(), file);
            }

            importObjects(owner, importFiles);
        }
        catch (CertificateException e) {
            throw new ImportExtractionException("unable to extract export archive", e);
        }
        catch (IOException e) {
            throw new ImportExtractionException("unable to extract export archive", e);
        }
        finally {
            if (tmpDir != null) {
                try {
                    FileUtils.deleteDirectory(tmpDir);
                }
                catch (IOException e) {
                    log.error("Failed to delete extracted export");
                    log.error(e);
                }
            }
            if (exportStream != null) {
                try {
                    exportStream.close();
                }
                catch (Exception e) {
                    // nothing we can do.
                }
            }
        }
    }
    
    @Transactional
    public void importObjects(Owner owner, Map<String, File> importFiles)
        throws IOException, ImporterException {
        
        File metadata = importFiles.get(ImportFile.META.fileName());

        // system level elements
        validateMetadata(ExporterMetadata.TYPE_SYSTEM, null, metadata);
        importRules(importFiles.get(ImportFile.RULES.fileName()).listFiles());
        importConsumerTypes(importFiles.get(ImportFile.CONSUMER_TYPE.fileName()).listFiles());
        Set<Product> importedProducts =
            importProducts(importFiles.get(ImportFile.PRODUCTS.fileName()).listFiles());

        // per user elements
        validateMetadata(ExporterMetadata.TYPE_PER_USER, owner, metadata);
        importConsumer(owner, importFiles.get(ImportFile.CONSUMER.fileName()));
        importEntitlements(owner, importedProducts,
            importFiles.get(ImportFile.ENTITLEMENTS.fileName()).listFiles());
        
        poolManager.refreshPools(owner);
    }
    
    public void importRules(File[] rulesFiles) throws IOException {
        RulesImporter importer = new RulesImporter(rulesCurator);
        
        // Only importing a single rules file now.
        Reader reader = null;
        try {
            reader = new FileReader(rulesFiles[0]);
            importer.importObject(reader);
        }
        finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
    
    public void importConsumerTypes(File[] consumerTypes) throws IOException {
        ConsumerTypeImporter importer = new ConsumerTypeImporter(consumerTypeCurator);
        Set<ConsumerType> consumerTypeObjs = new HashSet<ConsumerType>();
        for (File consumerType : consumerTypes) {
            Reader reader = null;
            try {
                reader = new FileReader(consumerType);
                consumerTypeObjs.add(importer.createObject(mapper, reader));
            } 
            finally {
                if (reader != null) {
                    reader.close();
                }
            }
        }
        importer.store(consumerTypeObjs);
    }

    public void importConsumer(Owner owner, File consumerFile) throws IOException,
        SyncDataFormatException {
        ConsumerImporter importer = new ConsumerImporter(ownerCurator);
        Reader reader = null;
        try {
            reader = new FileReader(consumerFile);
            ConsumerDto consumer = importer.createObject(mapper, reader);
            importer.store(owner, consumer);
        }
        finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
    
    public Set<Product> importProducts(File[] products) throws IOException {
        ProductImporter importer = new ProductImporter(productCurator, contentCurator);
        Set<Product> productsToImport = new HashSet<Product>();
        for (File product : products) {
            // Skip product.pem's, we just need the json to import:
            if (product.getName().endsWith(".json")) {
                log.debug("Import product: " + product.getName());
                Reader reader = null;
                try {
                    reader = new FileReader(product);
                    productsToImport.add(importer.createObject(mapper, reader));
                }
                finally {
                    if (reader != null) {
                        reader.close();
                    }
                }
            }
        }
        importer.store(productsToImport);
        // TODO: Do we need to cleanup unused products? Looked at this earlier and it
        // looks somewhat complex and a little bit dangerous, so we're leaving them
        // around for now.
        
        return productsToImport;
    }
    
    public void importEntitlements(Owner owner, Set<Product> products, File[] entitlements)
        throws IOException, SyncDataFormatException { 
        EntitlementImporter importer = new EntitlementImporter(subCurator, csCurator, sink);

        Map<String, Product> productsById = new HashMap<String, Product>();
        for (Product product : products) {
            productsById.put(product.getId(), product);
        }
        
        Set<Subscription> subscriptionsToImport = new HashSet<Subscription>();
        for (File entitlement : entitlements) {
            Reader reader = null;
            try {
                log.debug("Import entitlement: " + entitlement.getName());
                reader = new FileReader(entitlement);
                subscriptionsToImport.add(importer.importObject(mapper, reader, owner, productsById));
            } 
            finally {
                if (reader != null) {
                    reader.close();
                }
            }
        }
        
        importer.store(owner, subscriptionsToImport);
    }
        
    /**
     * Create a tar.gz archive of the exported directory.
     *
     * @param exportDir Directory where Candlepin data was exported.
     * @return File reference to the new archive tar.gz.
     */
    private File extractArchive(File tempDir, File exportFile) throws IOException {
        log.info("Extracting archive to: " + tempDir.getAbsolutePath());
        byte[] buf = new byte[1024];
        ZipInputStream zipinputstream = null;
        ZipEntry zipentry;
        zipinputstream = new ZipInputStream(new FileInputStream(exportFile));

        zipentry = zipinputstream.getNextEntry();
        while (zipentry != null) {
            //for each entry to be extracted
            String entryName = zipentry.getName();
            if (log.isDebugEnabled()) {
                log.debug("entryname " + entryName);
            }
            FileOutputStream fileoutputstream;
            File newFile = new File(entryName);
            String directory = newFile.getParent();
            if (directory != null) {
                new File(tempDir, directory).mkdirs();
            }
            
            fileoutputstream = new FileOutputStream(new File(tempDir, entryName));

            int n;
            while ((n = zipinputstream.read(buf, 0, 1024)) > -1) {
                fileoutputstream.write(buf, 0, n);
            }

            fileoutputstream.close(); 
            zipinputstream.closeEntry();
            zipentry = zipinputstream.getNextEntry();

        }

        zipinputstream.close();

        return new File(tempDir.getAbsolutePath(), "export");
    }
    
    private byte[] loadSignature(File signatureFile) throws IOException {
        FileInputStream signature = null;
        // signature is never going to be a huge file, therefore cast is a-okay
        byte[] signatureBytes = new byte[(int) signatureFile.length()]; 
        
        try {
            signature = new FileInputStream(signatureFile);
            
            int offset = 0;
            int numRead = 0;
            while (offset < signatureBytes.length && numRead >= 0) {
                numRead = signature.read(signatureBytes, offset,
                    signatureBytes.length - offset);
                offset += numRead;
            }
            return signatureBytes;
        }
        finally {
            if (signature != null) {
                try {
                    signature.close();
                }
                catch (IOException e) {
                    // nothing we can do about this
                }
            }
        }
    }
}
