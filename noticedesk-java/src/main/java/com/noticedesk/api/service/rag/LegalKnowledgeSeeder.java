package com.noticedesk.api.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LegalKnowledgeSeeder implements CommandLineRunner {

    private final RagStoreService ragStoreService;

    public record LegalSeedData(String actOrCircular, String sectionOrPara, String title, String content) {}

    public static final List<LegalSeedData> LEGAL_SEED_DATA = List.of(
            new LegalSeedData(
                    "CGST Act, 2017",
                    "Section 16(1) & (2)",
                    "Eligibility and conditions for taking input tax credit",
                    "Section 16(1): Every registered person shall be entitled to take credit of input tax charged on " +
                    "any supply of goods or services or both to him which are used or intended to be used in the course " +
                    "or furtherance of his business.\n" +
                    "Section 16(2): No registered person shall be entitled to the credit of any input tax unless (a) he is " +
                    "in possession of a tax invoice or debit note; (b) he has received the goods or services; (c) the tax " +
                    "charged has been paid to the Government; and (d) he has furnished the return under Section 39."
            ),
            new LegalSeedData(
                    "CGST Rules, 2017",
                    "Rule 36(4)",
                    "Documentary requirements and conditions for claiming ITC",
                    "Rule 36(4) capping ITC on un-uploaded invoices was inserted vide Notification No. 49/2019-Central Tax " +
                    "w.e.f. 9th October 2019. Prior to 9th October 2019, there was no statutory requirement under CGST Rules " +
                    "restricting ITC claiming based on auto-population in Form GSTR-2A. Rule 36(4) operates prospectively."
            ),
            new LegalSeedData(
                    "CBIC Circular No. 183/15/2022-GST",
                    "Para 4 & Para 5",
                    "Clarification on handling GSTR-3B vs GSTR-2A ITC mismatch for FY 2017-18 and FY 2018-19",
                    "Form GSTR-2A is a dynamic auto-populated statement for view facility and does not operate as a mandatory " +
                    "restriction for FY 2017-18 and 2018-19. Mismatches between GSTR-3B and GSTR-2A shall be verified by " +
                    "obtaining supplier certificates or CA/CMA certificates confirming that tax charged has been remitted to Govt."
            ),
            new LegalSeedData(
                    "CGST Act, 2017",
                    "Section 73(1)",
                    "Determination of tax not paid or short paid for non-fraud cases",
                    "Where it appears to the proper officer that any tax has not been paid or short paid or erroneously refunded, " +
                    "or where input tax credit has been wrongly availed or utilised for any reason other than fraud or willful " +
                    "misstatement, he shall serve notice requiring cause to be shown why demand should not be sustained."
            ),
            new LegalSeedData(
                    "CGST Act, 2017",
                    "Section 17(5)",
                    "Apportionment of credit and blocked credits",
                    "Section 17(5) specifies restricted categories where input tax credit shall not be available, including " +
                    "motor vehicles (except specified business uses), food and beverages, outdoor catering, beauty treatment, " +
                    "membership of a club, health and fitness centre, and goods or services used for personal consumption."
            ),
            new LegalSeedData(
                    "CGST Act, 2017",
                    "Section 74(1)",
                    "Determination of tax not paid or short paid by reason of fraud or willful misstatement",
                    "Where any tax has not been paid or short paid by reason of fraud, or any willful misstatement or suppression " +
                    "of facts to evade tax, the proper officer shall issue notice. Invoking Section 74 requires the department to " +
                    "establish deliberate intent and positive act of evasion, not mere non-disclosure or genuine interpretation differences."
            ),
            new LegalSeedData(
                    "Calcutta High Court",
                    "Suncraft Energy Pvt Ltd v. ACST (2023)",
                    "Recovery of tax must first be pursued against defaulting supplier before reversing buyer ITC",
                    "Held that the Revenue department cannot directly demand reversal of Input Tax Credit from the purchasing " +
                    "dealer without first taking action against the selling supplier who failed to deposit the tax collected. " +
                    "Recovery from the buyer is permissible only under exceptional circumstances after exhausting remedies against the seller."
            ),
            new LegalSeedData(
                    "Madras High Court",
                    "D.Y. Beathel Enterprises v. STO (2021)",
                    "Examination of seller mandatory when ITC mismatch arises",
                    "Where the purchasing dealer paid tax to the supplier via tax invoice and banking channels, the proper officer " +
                    "must examine the seller and initiate recovery proceedings against the defaulting supplier before raising demand " +
                    "on the buyer for GSTR-2A mismatch."
            ),
            new LegalSeedData(
                    "Supreme Court of India",
                    "Pushpam Pharmaceuticals Co. v. CCE (1995)",
                    "Meaning of suppression of facts for invoking extended limitation period",
                    "Suppression of facts implies a deliberate withholding of information with intent to evade tax. Mere omission " +
                    "or failure to declare facts in returns without fraudulent intent does not constitute suppression under statutory " +
                    "provisos."
            )
    );

    @Override
    public void run(String... args) {
        int count = seedData();
        log.info("LegalKnowledgeSeeder completed: {} legal chunks seeded.", count);
    }

    public int seedData() {
        int count = 0;
        for (LegalSeedData data : LEGAL_SEED_DATA) {
            ragStoreService.indexLegalChunk(
                    data.actOrCircular(),
                    data.sectionOrPara(),
                    data.title(),
                    data.content()
            );
            count++;
        }
        return count;
    }
}
