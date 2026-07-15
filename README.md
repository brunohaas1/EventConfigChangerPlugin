# EventConfigChangerPlugin - DarkBot Plugin

Plugin de automação e controle avançado de missões (Quests) para o DarkBot. Ele otimiza a tomada de decisões de rotas, troca de configurações da nave (Velocidade/Combate), controle de órbita de combate, prioridade de aceitação de missões e integração refinada com módulos de coleta e patrulha.

---

## 🚀 Principais Funcionalidades

### 1. 📅 Prioridade para Missões Diárias (Daily Quests)
- Sempre que a janela do `QuestGiver` é aberta em uma base de carregamento, o bot alterna automaticamente para a **aba de Missões Diárias** (índice `2`, a terceira aba).
- Ele aceita sequencialmente **todas** as missões diárias disponíveis.
- Apenas após concluir o aceite das diárias, ele retorna para a aba normal (índice `0`) e executa o fluxo padrão de missões secundárias.
- O estado das diárias é redefinido inteligentemente quando a janela é fechada, quando o bot sai da base, ou ao parar o script.

### 2. 🗺️ Resolução de Mapas para Missões Sequenciais
- O resolvedor de mapas (`MapResolver`) avalia **exclusivamente o requisito atualmente ativo** (`currentReq`) da missão.
- **Filtro de Objetivos (`getMatchingObjectiveLine`)**: Para missões sequenciais (ex: *Matar Devolarium no 1-3* e depois *Mordon no 1-4*), o plugin divide as instruções do objetivo global e extrai as coordenadas de mapa apenas da linha que corresponde à criatura atual, impedindo que o bot navegue prematuramente para o mapa do requisito futuro bloqueado.

### 3. 🛡️ Segurança contra Boss/Uber em Mapas Incorretos
- O resolvedor de NPCs possui uma proteção contra resoluções padrão incorretas em `1-1` (geradas por chaves de configuração sem sufixo de mapa).
- Se um NPC do tipo Boss ou Uber resolver para o mapa `1-1` (map ID 1), o plugin descarta a resolução e utiliza fallbacks específicos e determinísticos para direcioná-lo aos mapas corretos (X-2 para Bosses comuns, X-4 para Ubers, etc.).

### 4. ⚔️ Tecla de Munição Dedicada para PVP (`pvpAmmoKey`)
- Adiciona o campo configurável `pvpAmmoKey` (padrão tecla `'4'`) na interface do usuário do plugin.
- Durante combates de quests PVP, o bot clica periodicamente nesta tecla para disparar munição de elite sem comprometer as configurações globais de laser para NPCs normais.

### 5. 🎯 Correção de Raio de Combate (Auto-Circle)
- Intercepta a criação de novos NPCs não catalogados no banco de dados e força a redução do raio de órbita padrão de **`560.0`** para **`450.0`** metros. Isso evita que a nave circule os NPCs fora do alcance de tiro do laser.

### 6. 🔄 Modos de Voo e Combate Dinâmicos (Anti-Flicker)
- Sincroniza a configuração de navegação (Config 2 / Percurso) e combate (Config 1 / Ofensiva) utilizando `heroAPI.setRoamMode` e `heroAPI.setAttackMode`.
- Aplica um cooldown anti-oscilação apenas na transição de estados de voo, garantindo troca de configuração imediata respeitando o cooldown padrão de 5.5s do DarkOrbit.

---

## 🛠️ Como Compilar e Deployar

Este projeto utiliza Gradle para gerenciar dependências e compilação do arquivo final `.jar`.

### Requisitos:
- **Java Development Kit (JDK) 11**

### Passos para Compilação:
1. Abra um terminal PowerShell na pasta do projeto:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-11.0.12"
   .\gradlew.bat clean jar
   ```
2. Após o build bem-sucedido, o arquivo JAR compilado estará disponível em:
   `build/libs/EventConfigChanger.jar`

3. Mova o arquivo compilado para a pasta de plugins do seu DarkBot:
   ```powershell
   Copy-Item -Path "build/libs/EventConfigChanger.jar" -Destination "..\EventConfigChanger.jar" -Force
   ```

---

## 📂 Estrutura de Código Relevante

- **`QuestModule.java`**: O módulo principal de comportamento que executa no loop do bot, delegando para coleta de caixas/roaming e reavendo o controle para combate PVP ou troca de mapas.
- **`MapResolver.java`**: Controla toda a inteligência geográfica de caminhos, leitura de textos de objetivos em português, mapeamento de portais e coordenadas de NPCs.
- **`QuestGiverInteraction.java`**: Responsável pela navegação visual (cliques por coordenadas) e a máquina de estados não-bloqueante para prioridade de missões diárias.
- **`ConfigMarker.java`**: Sincroniza ativamente as chaves de marcação de caixas de minérios (ore) e NPCs no arquivo `config.json` do DarkBot em tempo de execução.
