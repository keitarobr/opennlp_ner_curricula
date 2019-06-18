//Create an OpenNLP model for Named Entity Recognition of Book Titles
//See tester at https://gist.github.com/johnmiedema/7e7330e1b9263267bdfc
package opennlp;


import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSampleDataStream;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.Span;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestCreateModel {

    class Profile {
        String id;
        String data;
        Set<NER> foundNERS = new HashSet<>();
        Set<NER> goldenNERS = new HashSet<>();

        public Profile(String id, String data) {
            this.id = id;
            this.data = data;
        }

        public String toString() {
            String prof = "Researcher: %s \n Golden NER: \n %s";
            return String.format(prof, this.id, StringUtils.join(this.goldenNERS, "\n"));
        }
    }

    class NER {
        String text;
        String type;
        Integer start;
        Integer end;

        public String toString() {
            return String.format("[%d..%d) %s => %s", start, end, type, text);
        }

        public NER(String text, Integer start, Integer end, String type) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NER ner = (NER) o;
            return Objects.equals(text, ner.text) &&
                    Objects.equals(start, ner.start) &&
                    Objects.equals(end, ner.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, start, end);
        }
    }


    public Map<String, TestCreateModel.Profile> loadData(int passo) throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
        Map<String, TestCreateModel.Profile> result = new HashMap<>();
        try (

                Connection connection = DriverManager.getConnection("jdbc:postgresql://10.0.1.220:5432/ner", "ner", "ner")) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT lattes_id, pt FROM lattes_ner where passo is null or passo >= " + passo);

            while (resultSet.next()) {
                result.put(resultSet.getString(1), new Profile(resultSet.getString(1), resultSet.getString(2)));
            }

            resultSet.close();
            return result;

        } /*catch (ClassNotFoundException e) {
            System.out.println("PostgreSQL JDBC driver not found.");
            e.printStackTrace();
        }*/ catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, TestCreateModel.Profile> loadTrainData(int passo) throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
        Map<String, TestCreateModel.Profile> result = new HashMap<>();
        try (

                Connection connection = DriverManager.getConnection("jdbc:postgresql://10.0.1.220:5432/ner", "ner", "ner")) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT lattes_id, pt, ner_pt_manual FROM lattes_ner where passo < " + passo);

            while (resultSet.next()) {
//                System.out.println("Loading training " + resultSet.getString(1));
                Profile p = new Profile(resultSet.getString(1), resultSet.getString(2));
                p.goldenNERS.addAll(convertGolden(resultSet.getString(3)));
                result.put(resultSet.getString(1), p);

//                System.out.println(p);
            }



            resultSet.close();
            return result;

        } /*catch (ClassNotFoundException e) {
            System.out.println("PostgreSQL JDBC driver not found.");
            e.printStackTrace();
        }*/ catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }


    public Map<String, TestCreateModel.Profile> loadTestData(int passo) throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
        Map<String, TestCreateModel.Profile> result = new HashMap<>();
        try (

                Connection connection = DriverManager.getConnection("jdbc:postgresql://10.0.1.220:5432/ner", "ner", "ner")) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT lattes_id, pt, ner_pt_manual FROM lattes_ner where passo >= " + passo);

            while (resultSet.next()) {
//                System.out.println("Loading testing " + resultSet.getString(1));
                Profile p = new Profile(resultSet.getString(1), resultSet.getString(2));
                p.goldenNERS.addAll(convertGolden(resultSet.getString(3)));
                result.put(resultSet.getString(1), p);

//                System.out.println(p);
            }



            resultSet.close();
            return result;

        } /*catch (ClassNotFoundException e) {
            System.out.println("PostgreSQL JDBC driver not found.");
            e.printStackTrace();
        }*/ catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<NER> convertGolden(String data) throws IOException {
        Pattern p = Pattern.compile("(<)([^>]+)(>)([^<]+)(</[A-Z]+>)");
        Matcher m = p.matcher(data);
        List<NER> result = new LinkedList<>();
        while (m.find()) {
            String type = m.group(1);
            if (type.equals("LOC")) {
                type =  "LOCAL";
            } else if (type.equals("PER")) {
                type =  "PESSOA";
            } else if (type.equals("ORG")) {
                type =  "ORGANIZACAO";
            } else {
                type =  "ABSTRACCAO";
            }
            String value = m.group(4);
            NER ner = new NER(value, 0, 0, type);
            result.add(ner);
        }

        Pattern pSub = Pattern.compile("<[^>]+>");
        Matcher mSub = pSub.matcher(data);
        data = mSub.replaceAll("");

        data = StringUtils.replaceAll(data, ",", ", ");
        String[] dataTokens = tokenize(data);

        int posInicial = 0;
        for (NER ner : result) {
//            System.out.println("Finding NER: " + ner);
            String[] nerData = tokenize(ner.text);
            while ( ! Arrays.equals(ArrayUtils.subarray(dataTokens, posInicial, posInicial + nerData.length),nerData)) {
                posInicial++;
            }

            ner.start = posInicial;
            ner.end = posInicial + nerData.length;
            posInicial = posInicial + nerData.length;
        }

        return result;
    }

    public void identifyNERs(Collection<Profile> data, NameFinderME model) throws IOException {
        for (Profile profile : data) {
            String[] tokens = tokenize(profile.data);
            Span[] ners = model.find(tokens);
            for (Span ner : ners) {
                profile.foundNERS.add(new NER(StringUtils.join(ArrayUtils.subarray(tokens, ner.getStart(), ner.getEnd()), " "), ner.getStart(), ner.getEnd(), ner.getType()));
            }
        }
    }



    @Test
    public void testNERs() throws ClassNotFoundException, IOException {
//        List<Profile> profiles = loadTrainData(2);
//        profiles.stream().forEach(prof -> System.out.println(prof));

        runStep(1);
        runStep(2);
        runStep(3);
        runStep(4);

    }

    private void runStep(int passo) throws ClassNotFoundException, IOException {
        Map<String, Profile> searchData = loadData(passo);
        Map<String, Profile> testData = loadTestData(passo);
        Map<String, Profile> trainData = loadTrainData(passo);

        findNERS(searchData, trainData);
        calculatePrecision(searchData, testData, passo);
    }

    private void calculatePrecision(Map<String, Profile> searchData, Map<String, Profile> testData, int passo) {

        int totalFound = 0;
        int totalMissing = 0;
        int totalWrong = 0;


        for (String id : testData.keySet()) {
            Profile pFound = searchData.get(id);
            Profile pGolden = testData.get(id);

//            System.out.println("Comparing " + id);

            int found = 0;
            int missing = 0;
            int wrong = 0;

            for (NER ner : pFound.foundNERS) {
                if (pGolden.goldenNERS.contains(ner)) {
                    found++;
                } else {
                    wrong++;
                }
            }

            for (NER ner : pGolden.goldenNERS) {
                if (! pFound.foundNERS.contains(ner)) {
                    missing++;
                }
            }

            totalFound += found;
            totalMissing += missing;
            totalWrong += wrong;

//            System.out.println(String.format("O pesquisador %s teve no ciclo %d Found=%d\tMissing=%d\tWrong=%d", id, passo, found, missing, wrong));
        }

        System.out.println(String.format("O passo %d houveram %d falsas, %d existentes, %d faltantes e %d NEs no total", passo, totalWrong, totalFound, totalMissing, totalFound + totalMissing));
    }

    private void findNERS(Map<String, Profile> searchData, Map<String, Profile> trainData) throws IOException {

        String trainText = FileUtils.readFileToString(new File(this.getClass().getResource("/harem.xml").getFile()), Charset.defaultCharset());

        for (Profile p : trainData.values()) {
            String textData = StringUtils.replaceEachRepeatedly(p.data, new String[] {"<PER>", "<LOCAL>", "<ORG>", "<MISC>", "</PER>", "</LOCAL>", "</ORG>", "</MISC>"},
                    new String[] {" <START:PESSOA> ", " <START:LOCAL> ", " <START:ORGANIZACAO> ", " <START:ABSTRACCAO> ", " <END> ", " <END> ", " <END> ", " <END> "});
            trainText += "\n--DOCSTART--\n\n" + textData;
        }

        ObjectStream fileStream = new PlainTextByLineStream(new StringReader(trainText));
        ObjectStream sampleStream = new NameSampleDataStream(fileStream);
        TokenNameFinderModel model = NameFinderME.train("pt-br", "train", sampleStream, Collections.<String, Object>emptyMap());

        NameFinderME nfm = new NameFinderME(model);

        identifyNERs(searchData.values(), nfm);

//        for (String id : searchData.keySet()) {
//            Profile p = searchData.get(id);
//
//
//
//        }
//
//
//        String data = "Rafael Luiz Cancian é doutor em Engenharia de Automação de Sistemas pela Universidade Federal de Santa Catarina, mestre e bacharel em Ciências da Computação pela UFSC. Atualmente é professor do Departamento de Informática e Estatística (INE) da Universidade Federal de Santa Catarina, coordenador e pesquisador do Laboratório de Integração de Software e Hardware (LISHA) do Centro Tecnológico da UFSC. Tem experiência na área de Engenharia e Ciência da Computação, com ênfase em software básico e integração software/hardware, atuando principalmente nos seguintes temas: Sistemas embarcados e cyber-físicos, Sistemas operacionais embarcados, Organização e arquitetura de computadores, Sistemas digitais, Biologia sintética e Simulação de sistemas. bachelor's at Ciências da Computação from Universidade Federal de Santa Catarina (1997), master's at Computer Science from Universidade Federal de Santa Catarina (2000) and doctorate at Engineering from Universidade Federal de Santa Catarina (2011). He is currently full professor at Universidade Federal de Santa Catarina. Has experience in Computer Science, focusing on Core Software, acting on the following subjects: sistemas embarcados, arquitetura de computadores, simulação de sistemas, redes de computadores and apresentação de artigos.";
//
//        String[] dataTokens = tokenize(data);
//
//        Span[] nes = nfm.find(dataTokens);
//
//        System.out.println("Before training...\n\n");
//
//        for (Span ne : nes) {
//            System.out.println(ne + " => " + StringUtils.join(ArrayUtils.subarray(dataTokens, ne.getStart(), ne.getEnd()), " "));
//        }
//
//        String trainData2 = "\n\n--DOCSTART--\n\n<PER>Antônio Augusto Fröhlich</PER> possui doutorado em <MISC>Engenharia da Computação</MISC> pela <ORG>Universidade Técnica de Berlim</ORG>, Mestrado em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG> e graduação em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal do Rio Grande do Sul</ORG>. Atualmente é professor do <ORG>Departamento de Informática e Estatística</ORG> (<ORG>INE</ORG>) e do <MISC>Programa de Pós-Graduação em Ciência da Computação</MISC> (<ORG>PPGCC</ORG>) da <ORG>Universidade Federal de Santa Catarina</ORG> (<ORG>UFSC</ORG>), onde coordena também o <ORG>Laboratório de Integração de Software e Hardware</ORG> (<ORG>LISHA</ORG>). O <PER>Prof. Fröhlich</PER> é autor de inúmeras publicações na área de sistemas embarcados e sistemas operacionais, áreas nas quais coordena e executa uma série de projetos de pesquisa e desenvolvimento.";
//        trainData2 += "\n\n\n--DOCSTART--\n\nPossui graduação em <MISC>Engenharia de Computação</MISC> pela <ORG>Unicamp</ORG> (em 2001), mestrado (em 2003) e doutorado (em 2009) em <MISC>Engenharia Elétrica</MISC>, na área de <MISC>Engenharia de Computação</MISC>, pela <ORG>FEEC</ORG>/<LOC>Unicamp</LOC>. Foi professor do <ORG>DCC</ORG>/<ORG>CCT</ORG>/<ORG>UDESC</ORG> (de <MISC>Jul/2004</MISC> a <MISC>Ago/2014</MISC>) e atualmente é professor do <ORG>INE</ORG>/<ORG>CTC</ORG>/<MISC>UFSC</MISC> (desde <MISC>Ago/2014</MISC>). Atua principalmente nos seguintes temas: <MISC>Processamento de Imagens</MISC>, <MISC>Visão Computacional</MISC> e <MISC>Reconhecimento de Padrões</MISC>.";
//        trainData2 += "\n\n\n--DOCSTART--\n\n<PER>Andreia Zanella</PER> é graduada em <MISC>Matemática</MISC> pela <ORG>Universidade de Passo Fundo</ORG> (2004) e especialista em <MISC>Estatística</MISC> e <MISC>Modelagem Quantitativa</MISC> pela <ORG>Universidade Federal de Santa Maria</ORG> (2006). Em 2008, recebeu o grau de mestre em <MISC>Engenharia de Produção</MISC> pela <ORG>Universidade Federal de Santa Maria</ORG>. Em 2014, recebeu o grau de doutora em <MISC>Engenharia Industrial e Gestão</MISC> pela <ORG>Faculdade Engenharia da Universidade do Porto</ORG>. Trabalhou como professora substituta no <ORG>Departamento de Estatística</ORG> da <ORG>Universidade Federal de Santa Maria</ORG> entre 2007 e 2009. Foi professora assistente convidada e bolsista de pós-doutorado na <ORG>Faculdade de Engenharia da Universidade do Porto</ORG>. Atualmente é <MISC>Professora Adjunta</MISC> no <ORG>Departamento de Informática e Estatística</ORG> da <ORG>Universidade Federal de Santa Catarina</ORG>.";
//        trainData2 += "\n\n\n--DOCSTART--\n\nÉ <PER>Professor Titular</PER> da <ORG>Universidade Federal de Santa Catarina</ORG> e possui graduação em <MISC>Ciências da Computação</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG> (1989) e <MISC>Doutorado Acadêmico</MISC> (Dr. rer.nat.) em <MISC>Ciências da Computação</MISC> pela <ORG>Universidade de Kaiserslautern</ORG> (1996). Atualmente é <PER>Professor Titular</PER> da <ORG>Universidade Federal de Santa Catarina</ORG>, onde é professor do <MISC>Programa de Pós-graduação em Ciência da Computação</MISC> e dos cursos de graduação em <MISC>Ciências da Computação</MISC>, <MISC>Sistemas de Informação e Medicina</MISC>. É também professor e orientador de doutorado do <MISC>Programa de Pós-Graduação em Ciências da Computação da Universidade Federal do Paraná</MISC> - <ORG>UFPR</ORG>. Tem experiência nas áreas de <MISC>Produção de Conteúdo para TV Digital Interativa</MISC>, <MISC>Informática em Saúde</MISC>, <MISC>Processamento e Análise de Imagens</MISC> e <MISC>Engenharia Biomédica</MISC>, com ênfase em <MISC>Telemedicina</MISC>, <MISC>Telerradiologia</MISC>, <MISC>Sistemas de Auxílio ao Diagnóstico por Imagem</MISC> e <MISC>Processamento de Imagens Médicas</MISC>, com foco nos seguintes temas: analise inteligente de imagens, <MISC>DICOM</MISC>, <MISC>CBIR</MISC>, informática médica, visão computacional e <MISC>PACS</MISC>. Coordena o <ORG>Instituto Nacional de Ciência e Tecnologia para Convergência Digital</ORG> - <ORG>INCoD</ORG>. É também <PER>Coordenador Técnico</PER> da <ORG>Rede Catarinense de Telemedicina</ORG> (<ORG>RCTM</ORG>), coordenador do <ORG>Grupo de Trabalho Normalização em Telessaúde do Comitê Permanente de Telessaúde</ORG>/<ORG>Ministério da Saúde</ORG> e membro fundador e ex-coordenador da <ORG>Comissão Informática em Saúde da ABNT</ORG> - <ORG>ABNT/CEET</ORG> 00:001.78. Atualmente também é membro da comissão <ORG>ISO/TC 215 - Health Informatics</ORG>. Foi coordenador da <MISC>RFP6</MISC> - Conteúdo - do <ORG>SBTVD</ORG> - <ORG>Sistema Brasileiro de TV Digital</ORG>/<ORG>Ministério das Comunicações</ORG>. Foi Coordenador do <ORG>Núcleo de Telessaúde de Santa Catarina</ORG> no âmbito do <ORG>Programa Telessaúde Brasil</ORG> do <ORG>Ministério da Saúde</ORG> e da <ORG>OPAS</ORG> - <ORG>Organização Pan-Americana de Saúde</ORG> e Coordenador do <ORG>Núcleo Santa Catarina da RUTE</ORG> - <ORG>Rede Universitária de Telemedicina</ORG>.";
//        trainData2 += "\n\n\n--DOCSTART--\n\nProfessor Adjunto do <ORG>Departamento de Computação da Universidade Federal de Santa Catarina</ORG> (<LOC>Campus Araranguá</LOC>) desde 2014. Possui o grau de bacharel em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal de Goiás</ORG>. Concluiu o mestrado e doutorado em <MISC>Ciência da Computação</MISC> na <ORG>Universidade de São Paulo</ORG>. Participa dos <ORG>Grupos de Pesquisa Computação Científica e Teoria da Computação, Combinatória e Otimização</ORG>. Suas pesquisas concentram-se nas áreas de <MISC>Algoritmos</MISC>, <MISC>Otimização</MISC> e <MISC>Grafos</MISC>.";
//        trainData2 += "\n\n\n--DOCSTART--\n\nPossui graduação em <MISC>Engenharia de Alimentos</MISC> pela <ORG>UPF</ORG> (2003), Mestrado em <MISC>Engenharia de Alimentos</MISC> (2008) e Doutorado (2013) e Pós-Doutorado (2014/2015) em <MISC>Engenharia Química</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG>, tendo realizado parte do doutorado no <ORG>Institut National Polytechnique de Lorraine</ORG> na <ORG>Ecole Nationale Supérieure des Industries Chimiques</ORG> (<LOC>França</LOC>). Atua como professor no <ORG>Departamento de Informática e Estatística</ORG> na <ORG>Universidade Federal de Santa Catarina</ORG>. Tem experiência nas áreas de <MISC>Modelagem Matemática</MISC>, <MISC>Planejamento Experimental</MISC>, <MISC>Otimização</MISC>, <MISC>Métodos Estatísticos</MISC> e <MISC>Análise numérica</MISC>. Trabalhou com Processos de Extração, Separação e Processos Biotecnológicos com foco em temas: como simulação, otimização e avaliação econômica de processos produtivos da indústria química e alimentos. Possui experiência com modelagem matemática de processos de separação por cromatografia gasosa, supercrítica (<MISC>SFC</MISC>) e líquida (<MISC>HPLC</MISC>), bem como o projeto de unidades, sistemas e equipamentos de alta pressão. Atuou em projetos de consultoria tecnológica nas indústrias química e de alimentos (<ORG>BR Foods</ORG>).";
//
//
//        trainData2 = StringUtils.replaceEachRepeatedly(trainData2, new String[] {"<PER>", "<LOCAL>", "<ORG>", "<MISC>", "</PER>", "</LOCAL>", "</ORG>", "</MISC>"},
//                new String[] {" <START:PESSOA> ", " <START:LOCAL> ", " <START:ORGANIZACAO> ", " <START:ABSTRACCAO> ", " <END> ", " <END> ", " <END> ", " <END> "});
//
////        FileUtils.writeStringToFile(new File("train.xml"), trainData2, Charset.defaultCharset());
//
//        fileStream = new PlainTextByLineStream(new StringReader(trainData + trainData2));
//        sampleStream = new NameSampleDataStream(fileStream);
//        model = NameFinderME.train("pt-br", "train", sampleStream, Collections.<String, Object>emptyMap());
//
//        nfm = new NameFinderME(model);








    }


    @Test
    public void testTreinarModel() throws IOException {

        String trainData = FileUtils.readFileToString(new File(this.getClass().getResource("/harem.xml").getFile()), Charset.defaultCharset());
//        trainData = StringUtils.replaceEachRepeatedly(trainData, new String[] {"START:PESSOA", "START:LOCAL", "START:ORGANIZACAO", "START:ABSTRACCAO"},
//                                                                 new String[] {"PER", "LOC", "ORG", "MISC"});

//        FileReader fileReader = new FileReader(this.getClass().getResource("/harem.xml").getFile());
        ObjectStream fileStream = new PlainTextByLineStream(new StringReader(trainData));
        ObjectStream sampleStream = new NameSampleDataStream(fileStream);

        TokenNameFinderModel model = NameFinderME.train("pt-br", "train", sampleStream, Collections.<String, Object>emptyMap());

        NameFinderME nfm = new NameFinderME(model);

        String data = "Rafael Luiz Cancian é doutor em Engenharia de Automação de Sistemas pela Universidade Federal de Santa Catarina, mestre e bacharel em Ciências da Computação pela UFSC. Atualmente é professor do Departamento de Informática e Estatística (INE) da Universidade Federal de Santa Catarina, coordenador e pesquisador do Laboratório de Integração de Software e Hardware (LISHA) do Centro Tecnológico da UFSC. Tem experiência na área de Engenharia e Ciência da Computação, com ênfase em software básico e integração software/hardware, atuando principalmente nos seguintes temas: Sistemas embarcados e cyber-físicos, Sistemas operacionais embarcados, Organização e arquitetura de computadores, Sistemas digitais, Biologia sintética e Simulação de sistemas. bachelor's at Ciências da Computação from Universidade Federal de Santa Catarina (1997), master's at Computer Science from Universidade Federal de Santa Catarina (2000) and doctorate at Engineering from Universidade Federal de Santa Catarina (2011). He is currently full professor at Universidade Federal de Santa Catarina. Has experience in Computer Science, focusing on Core Software, acting on the following subjects: sistemas embarcados, arquitetura de computadores, simulação de sistemas, redes de computadores and apresentação de artigos.";

        String[] dataTokens = tokenize(data);

        Span[] nes = nfm.find(dataTokens);

        System.out.println("Before training...\n\n");

        for (Span ne : nes) {
            System.out.println(ne + " => " + StringUtils.join(ArrayUtils.subarray(dataTokens, ne.getStart(), ne.getEnd()), " "));
        }

        String trainData2 = "\n\n--DOCSTART--\n\n<PER>Antônio Augusto Fröhlich</PER> possui doutorado em <MISC>Engenharia da Computação</MISC> pela <ORG>Universidade Técnica de Berlim</ORG>, Mestrado em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG> e graduação em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal do Rio Grande do Sul</ORG>. Atualmente é professor do <ORG>Departamento de Informática e Estatística</ORG> (<ORG>INE</ORG>) e do <MISC>Programa de Pós-Graduação em Ciência da Computação</MISC> (<ORG>PPGCC</ORG>) da <ORG>Universidade Federal de Santa Catarina</ORG> (<ORG>UFSC</ORG>), onde coordena também o <ORG>Laboratório de Integração de Software e Hardware</ORG> (<ORG>LISHA</ORG>). O <PER>Prof. Fröhlich</PER> é autor de inúmeras publicações na área de sistemas embarcados e sistemas operacionais, áreas nas quais coordena e executa uma série de projetos de pesquisa e desenvolvimento.";
        trainData2 += "\n\n\n--DOCSTART--\n\nPossui graduação em <MISC>Engenharia de Computação</MISC> pela <ORG>Unicamp</ORG> (em 2001), mestrado (em 2003) e doutorado (em 2009) em <MISC>Engenharia Elétrica</MISC>, na área de <MISC>Engenharia de Computação</MISC>, pela <ORG>FEEC</ORG>/<LOC>Unicamp</LOC>. Foi professor do <ORG>DCC</ORG>/<ORG>CCT</ORG>/<ORG>UDESC</ORG> (de <MISC>Jul/2004</MISC> a <MISC>Ago/2014</MISC>) e atualmente é professor do <ORG>INE</ORG>/<ORG>CTC</ORG>/<MISC>UFSC</MISC> (desde <MISC>Ago/2014</MISC>). Atua principalmente nos seguintes temas: <MISC>Processamento de Imagens</MISC>, <MISC>Visão Computacional</MISC> e <MISC>Reconhecimento de Padrões</MISC>.";
        trainData2 += "\n\n\n--DOCSTART--\n\n<PER>Andreia Zanella</PER> é graduada em <MISC>Matemática</MISC> pela <ORG>Universidade de Passo Fundo</ORG> (2004) e especialista em <MISC>Estatística</MISC> e <MISC>Modelagem Quantitativa</MISC> pela <ORG>Universidade Federal de Santa Maria</ORG> (2006). Em 2008, recebeu o grau de mestre em <MISC>Engenharia de Produção</MISC> pela <ORG>Universidade Federal de Santa Maria</ORG>. Em 2014, recebeu o grau de doutora em <MISC>Engenharia Industrial e Gestão</MISC> pela <ORG>Faculdade Engenharia da Universidade do Porto</ORG>. Trabalhou como professora substituta no <ORG>Departamento de Estatística</ORG> da <ORG>Universidade Federal de Santa Maria</ORG> entre 2007 e 2009. Foi professora assistente convidada e bolsista de pós-doutorado na <ORG>Faculdade de Engenharia da Universidade do Porto</ORG>. Atualmente é <MISC>Professora Adjunta</MISC> no <ORG>Departamento de Informática e Estatística</ORG> da <ORG>Universidade Federal de Santa Catarina</ORG>.";
        trainData2 += "\n\n\n--DOCSTART--\n\nÉ <PER>Professor Titular</PER> da <ORG>Universidade Federal de Santa Catarina</ORG> e possui graduação em <MISC>Ciências da Computação</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG> (1989) e <MISC>Doutorado Acadêmico</MISC> (Dr. rer.nat.) em <MISC>Ciências da Computação</MISC> pela <ORG>Universidade de Kaiserslautern</ORG> (1996). Atualmente é <PER>Professor Titular</PER> da <ORG>Universidade Federal de Santa Catarina</ORG>, onde é professor do <MISC>Programa de Pós-graduação em Ciência da Computação</MISC> e dos cursos de graduação em <MISC>Ciências da Computação</MISC>, <MISC>Sistemas de Informação e Medicina</MISC>. É também professor e orientador de doutorado do <MISC>Programa de Pós-Graduação em Ciências da Computação da Universidade Federal do Paraná</MISC> - <ORG>UFPR</ORG>. Tem experiência nas áreas de <MISC>Produção de Conteúdo para TV Digital Interativa</MISC>, <MISC>Informática em Saúde</MISC>, <MISC>Processamento e Análise de Imagens</MISC> e <MISC>Engenharia Biomédica</MISC>, com ênfase em <MISC>Telemedicina</MISC>, <MISC>Telerradiologia</MISC>, <MISC>Sistemas de Auxílio ao Diagnóstico por Imagem</MISC> e <MISC>Processamento de Imagens Médicas</MISC>, com foco nos seguintes temas: analise inteligente de imagens, <MISC>DICOM</MISC>, <MISC>CBIR</MISC>, informática médica, visão computacional e <MISC>PACS</MISC>. Coordena o <ORG>Instituto Nacional de Ciência e Tecnologia para Convergência Digital</ORG> - <ORG>INCoD</ORG>. É também <PER>Coordenador Técnico</PER> da <ORG>Rede Catarinense de Telemedicina</ORG> (<ORG>RCTM</ORG>), coordenador do <ORG>Grupo de Trabalho Normalização em Telessaúde do Comitê Permanente de Telessaúde</ORG>/<ORG>Ministério da Saúde</ORG> e membro fundador e ex-coordenador da <ORG>Comissão Informática em Saúde da ABNT</ORG> - <ORG>ABNT/CEET</ORG> 00:001.78. Atualmente também é membro da comissão <ORG>ISO/TC 215 - Health Informatics</ORG>. Foi coordenador da <MISC>RFP6</MISC> - Conteúdo - do <ORG>SBTVD</ORG> - <ORG>Sistema Brasileiro de TV Digital</ORG>/<ORG>Ministério das Comunicações</ORG>. Foi Coordenador do <ORG>Núcleo de Telessaúde de Santa Catarina</ORG> no âmbito do <ORG>Programa Telessaúde Brasil</ORG> do <ORG>Ministério da Saúde</ORG> e da <ORG>OPAS</ORG> - <ORG>Organização Pan-Americana de Saúde</ORG> e Coordenador do <ORG>Núcleo Santa Catarina da RUTE</ORG> - <ORG>Rede Universitária de Telemedicina</ORG>.";
        trainData2 += "\n\n\n--DOCSTART--\n\nProfessor Adjunto do <ORG>Departamento de Computação da Universidade Federal de Santa Catarina</ORG> (<LOC>Campus Araranguá</LOC>) desde 2014. Possui o grau de bacharel em <MISC>Ciência da Computação</MISC> pela <ORG>Universidade Federal de Goiás</ORG>. Concluiu o mestrado e doutorado em <MISC>Ciência da Computação</MISC> na <ORG>Universidade de São Paulo</ORG>. Participa dos <ORG>Grupos de Pesquisa Computação Científica e Teoria da Computação, Combinatória e Otimização</ORG>. Suas pesquisas concentram-se nas áreas de <MISC>Algoritmos</MISC>, <MISC>Otimização</MISC> e <MISC>Grafos</MISC>.";
        trainData2 += "\n\n\n--DOCSTART--\n\nPossui graduação em <MISC>Engenharia de Alimentos</MISC> pela <ORG>UPF</ORG> (2003), Mestrado em <MISC>Engenharia de Alimentos</MISC> (2008) e Doutorado (2013) e Pós-Doutorado (2014/2015) em <MISC>Engenharia Química</MISC> pela <ORG>Universidade Federal de Santa Catarina</ORG>, tendo realizado parte do doutorado no <ORG>Institut National Polytechnique de Lorraine</ORG> na <ORG>Ecole Nationale Supérieure des Industries Chimiques</ORG> (<LOC>França</LOC>). Atua como professor no <ORG>Departamento de Informática e Estatística</ORG> na <ORG>Universidade Federal de Santa Catarina</ORG>. Tem experiência nas áreas de <MISC>Modelagem Matemática</MISC>, <MISC>Planejamento Experimental</MISC>, <MISC>Otimização</MISC>, <MISC>Métodos Estatísticos</MISC> e <MISC>Análise numérica</MISC>. Trabalhou com Processos de Extração, Separação e Processos Biotecnológicos com foco em temas: como simulação, otimização e avaliação econômica de processos produtivos da indústria química e alimentos. Possui experiência com modelagem matemática de processos de separação por cromatografia gasosa, supercrítica (<MISC>SFC</MISC>) e líquida (<MISC>HPLC</MISC>), bem como o projeto de unidades, sistemas e equipamentos de alta pressão. Atuou em projetos de consultoria tecnológica nas indústrias química e de alimentos (<ORG>BR Foods</ORG>).";


        trainData2 = StringUtils.replaceEachRepeatedly(trainData2, new String[] {"<PER>", "<LOCAL>", "<ORG>", "<MISC>", "</PER>", "</LOCAL>", "</ORG>", "</MISC>"},
                                                                  new String[] {" <START:PESSOA> ", " <START:LOCAL> ", " <START:ORGANIZACAO> ", " <START:ABSTRACCAO> ", " <END> ", " <END> ", " <END> ", " <END> "});

//        FileUtils.writeStringToFile(new File("train.xml"), trainData2, Charset.defaultCharset());

        fileStream = new PlainTextByLineStream(new StringReader(trainData + trainData2));
        sampleStream = new NameSampleDataStream(fileStream);
        model = NameFinderME.train("pt-br", "train", sampleStream, Collections.<String, Object>emptyMap());

        nfm = new NameFinderME(model);

//        String data = "Rafael Luiz Cancian é doutor em Engenharia de Automação de Sistemas pela Universidade Federal de Santa Catarina, mestre e bacharel em Ciências da Computação pela UFSC. Atualmente é professor do Departamento de Informática e Estatística (INE) da Universidade Federal de Santa Catarina, coordenador e pesquisador do Laboratório de Integração de Software e Hardware (LISHA) do Centro Tecnológico da UFSC. Tem experiência na área de Engenharia e Ciência da Computação, com ênfase em software básico e integração software/hardware, atuando principalmente nos seguintes temas: Sistemas embarcados e cyber-físicos, Sistemas operacionais embarcados, Organização e arquitetura de computadores, Sistemas digitais, Biologia sintética e Simulação de sistemas. bachelor's at Ciências da Computação from Universidade Federal de Santa Catarina (1997), master's at Computer Science from Universidade Federal de Santa Catarina (2000) and doctorate at Engineering from Universidade Federal de Santa Catarina (2011). He is currently full professor at Universidade Federal de Santa Catarina. Has experience in Computer Science, focusing on Core Software, acting on the following subjects: sistemas embarcados, arquitetura de computadores, simulação de sistemas, redes de computadores and apresentação de artigos.";

//        dataTokens = tokenize(data);

        System.out.println("After training...\n\n");
        nes = nfm.find(dataTokens);

        for (Span ne : nes) {
            System.out.println(ne + " => " + StringUtils.join(ArrayUtils.subarray(dataTokens, ne.getStart(), ne.getEnd()), " "));
        }

    }

    public String[] tokenize(String sentence) throws IOException{
        InputStream inputStreamTokenizer = getClass().getResourceAsStream("/pt-token.bin");
        TokenizerModel tokenModel = new TokenizerModel(inputStreamTokenizer);
        TokenizerME tokenizer = new TokenizerME(tokenModel);
        return tokenizer.tokenize(sentence);
    }
}
