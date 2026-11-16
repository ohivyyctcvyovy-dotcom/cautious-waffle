# 455 — Leitor de Tela Inteligente

Aplicativo Android nativo. O usuário escolhe o idioma de origem e inicia a leitura manualmente. Não há leitura automática nem troca silenciosa de idioma.

Idiomas previstos: inglês, chinês, japonês, coreano, hindi, tailandês e vietnamita. A tradução é para português usando modelos locais do ML Kit. O OCR usa os reconhecedores de script do ML Kit; tailandês usa Tesseract e seu modelo local.

Privacidade: o texto e as capturas são processados no aparelho. A conexão é usada somente para baixar os pacotes de tradução e o modelo de OCR tailandês; o conteúdo da tela não é enviado.

Uso: instale um idioma, ative o serviço em Configurações > Acessibilidade, toque no botão flutuante 455, escolha Selecionar área ou Ler tela inteira e confirme manualmente.
