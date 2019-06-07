package de.upb.cs.dice.opal.conversion.writer;


import de.upb.cs.dice.opal.conversion.config.QualityMetricsConfiguration;
import de.upb.cs.dice.opal.conversion.model.Ckan;
import de.upb.cs.dice.opal.conversion.repository.CkanRepository;
import de.upb.cs.dice.opal.conversion.utility.JenaModelToDcatJsonConverter;
import de.upb.cs.dice.opal.conversion.utility.RDFUtility;
import org.apache.jena.rdf.model.*;
import org.dice_research.opal.common.vocabulary.Dqv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.Optional;

@Component
public class CKANWriter {

    private static final Logger logger = LoggerFactory.getLogger(CKANWriter.class);

    private JenaModelToDcatJsonConverter jenaModelToDcatJsonConverter;

    private final QualityMetricsConfiguration qualityMetricsConfiguration;
    private final CkanRepository ckanRepository;

    @Value("${info.ckan.url}")
    private String CKAN_URL;

    @Value("${info.duplicateName.appendNumber}")
    private boolean appendNumber;

    @Autowired
    public CKANWriter(QualityMetricsConfiguration qualityMetricsConfiguration, CkanRepository ckanRepository) {
        this.qualityMetricsConfiguration = qualityMetricsConfiguration;
        this.ckanRepository = ckanRepository;
    }


    @RabbitListener(queues = "#{writerQueueCKAN}")
    public void dump(byte[] bytes) {
        try {
            if (bytes == null) return;
            Model model = RDFUtility.deserialize(bytes);

            if (jenaModelToDcatJsonConverter == null) {
                synchronized (this) {
                    Iterable<Ckan> all = ckanRepository.findAll();
                    if (!all.iterator().hasNext()) return;
                    Ckan ckan = all.iterator().next();
                    if (jenaModelToDcatJsonConverter == null)
                        jenaModelToDcatJsonConverter =
                                new JenaModelToDcatJsonConverter(CKAN_URL, ckan.getApiKey(), appendNumber);
                }
            }

            AbstractMap.SimpleEntry<StringBuilder, StringBuilder> modelJson = jenaModelToDcatJsonConverter.getModelJson(model);
            StringBuilder json = modelJson.getKey();
            StringBuilder extras = modelJson.getValue();

            addQualityMetrics(extras, model);

            json.append(String.format(",\"%s\":[%s]", "extras", extras));
            String payload = String.format("{%s}", json);


            String url = CKAN_URL + "/api/3/action/package_create";
            jenaModelToDcatJsonConverter.fireAndForgetCallPostCKAN(url, payload);
        } catch (Exception e) {
            logger.error("An error occurred in dumping model", e);
        }
    }

    private void addQualityMetrics(StringBuilder extras, Model model) {
        qualityMetricsConfiguration.getMeasurementResource().forEach((key, resource) -> {
            ResIterator resIterator =
                    model.listResourcesWithProperty(Dqv.IS_MEASUREMENT_OF, ResourceFactory.createResource(resource));
            if (resIterator.hasNext()) {
                Resource measurement = resIterator.nextResource();
                NodeIterator nodeIterator = model.listObjectsOfProperty(measurement, Dqv.HAS_VALUE);
                if (nodeIterator.hasNext()) {
                    RDFNode rdfNode = nodeIterator.nextNode();
                    if (rdfNode.isLiteral()) {
                        try {
                            extras.append(String.format("%s{\"key\":\"%s\", \"value\":\"%s\"}",
                                    extras.length() > 0 ? "," : "",
                                    qualityMetricsConfiguration.getMeasurementName().get(key),
                                    rdfNode.asLiteral().getString()));
                        } catch (Exception e) {
                            //ignore
                        }
                    }
                }
            }
        });
    }

}
