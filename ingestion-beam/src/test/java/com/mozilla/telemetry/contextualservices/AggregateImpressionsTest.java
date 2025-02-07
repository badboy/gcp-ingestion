package com.mozilla.telemetry.contextualservices;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.mozilla.telemetry.ingestion.core.Constant.Attribute;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AggregateImpressionsTest {

  @Rule
  public final transient TestPipeline pipeline = TestPipeline.create();

  @Test
  public void testBuildAggregateUrl() {
    List<KV<String, Long>> input = ImmutableList.of(KV.of("https://test.com", 4L));

    PCollection<PubsubMessage> output = pipeline.apply(Create.of(input))
        .apply(Window.into(FixedWindows.of(Duration.standardMinutes(5))))
        .apply(ParDo.of(new AggregateImpressions.BuildAggregateUrl()));

    PAssert.that(output).satisfies(messages -> {

      Assert.assertEquals(Iterables.size(messages), 1);

      String aggregatedUrl = Iterables.get(messages, 0).getAttribute(Attribute.REPORTING_URL);
      ParsedReportingUrl parsedUrl = new ParsedReportingUrl(aggregatedUrl);

      Assert.assertTrue(aggregatedUrl.startsWith("https://test.com"));
      Assert.assertEquals(parsedUrl.getQueryParam("impressions"), "4");
      Assert.assertNotNull(parsedUrl.getQueryParam("end-timestamp"));
      Assert.assertFalse(parsedUrl.getQueryParam("end-timestamp").isEmpty());
      Assert.assertNotNull(parsedUrl.getQueryParam("begin-timestamp"));
      Assert.assertFalse(parsedUrl.getQueryParam("begin-timestamp").isEmpty());
      Assert.assertNotEquals(parsedUrl.getQueryParam("begin-timestamp"),
          parsedUrl.getQueryParam("end-timestamp"));

      return null;
    });

    pipeline.run();
  }

  @Test
  public void testGetAggregationKey() {
    String url = "http://test.com?country-code=US&abc=abc&def=a";
    Map<String, String> attributes = Collections.singletonMap(Attribute.REPORTING_URL, url);
    PubsubMessage message = new PubsubMessage(new byte[] {}, attributes);

    String aggKey = AggregateImpressions.getAggregationKey(message);

    // Should return url with sorted query params
    Assert.assertEquals(aggKey, "http://test.com?abc=abc&country-code=US&def=a");
  }

  @Test
  public void testAggregation() {
    Map<String, String> attributesUrl1 = Collections.singletonMap(Attribute.REPORTING_URL,
        String.format("https://test.com?%s=US&%s=", ParsedReportingUrl.PARAM_COUNTRY_CODE,
            ParsedReportingUrl.PARAM_REGION_CODE));
    Map<String, String> attributesUrl2 = Collections.singletonMap(Attribute.REPORTING_URL,
        String.format("https://test.com?%s=DE&%s=", ParsedReportingUrl.PARAM_COUNTRY_CODE,
            ParsedReportingUrl.PARAM_REGION_CODE));

    List<PubsubMessage> input = ImmutableList.of(new PubsubMessage(new byte[0], attributesUrl1),
        new PubsubMessage(new byte[0], attributesUrl2),
        new PubsubMessage(new byte[0], attributesUrl1),
        new PubsubMessage(new byte[0], attributesUrl1),
        new PubsubMessage(new byte[0], attributesUrl2));

    PCollection<PubsubMessage> output = pipeline.apply(Create.of(input))
        .apply(AggregateImpressions.of("10m"));

    PAssert.that(output).satisfies(messages -> {

      Assert.assertEquals(Iterables.size(messages), 2);

      messages.forEach(message -> {
        String reportingUrl = message.getAttribute(Attribute.REPORTING_URL);
        ParsedReportingUrl parsedUrl = new ParsedReportingUrl(reportingUrl);

        String country = parsedUrl.getQueryParam(ParsedReportingUrl.PARAM_COUNTRY_CODE);
        if ("US".equals(country)) {
          Assert.assertEquals(parsedUrl.getQueryParam(ParsedReportingUrl.PARAM_IMPRESSIONS), "3");
        } else if ("DE".equals(country)) {
          Assert.assertEquals(parsedUrl.getQueryParam(ParsedReportingUrl.PARAM_IMPRESSIONS), "2");
        } else {
          throw new IllegalArgumentException("unknown country value");
        }

        // Parameters with no values should still be included
        Assert.assertTrue(
            reportingUrl.contains(String.format("%s=", ParsedReportingUrl.PARAM_REGION_CODE)));

        long windowSize = Long
            .parseLong(parsedUrl.getQueryParam(ParsedReportingUrl.PARAM_TIMESTAMP_END))
            - Long.parseLong(parsedUrl.getQueryParam(ParsedReportingUrl.PARAM_TIMESTAMP_BEGIN));
        Assert.assertEquals(windowSize, 600L);
      });

      return null;
    });

    pipeline.run();
  }
}
