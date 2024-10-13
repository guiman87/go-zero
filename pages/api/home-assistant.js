export default async function handler(req, res) {
    if (req.method !== 'POST') {
      res.status(405).json({ error: 'Method not allowed' });
      return;
    }
  
    const { text } = req.body;
  
    if (!text) {
      res.status(400).json({ error: 'Text is required' });
      return;
    }
  
    const homeAssistantUrl = process.env.HOME_ASSISTANT_URL;
    const homeAssistantToken = process.env.HOME_ASSISTANT_TOKEN;
  
    try {
      const response = await fetch(homeAssistantUrl, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${homeAssistantToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: text,
          language: 'en',
        }),
      });
  
      if (!response.ok) {
        const errorData = await response.json();
        res.status(response.status).json({ error: errorData });
        return;
      }
  
      const data = await response.json();
      res.status(200).json(data);
  
    } catch (error) {
      console.error('Error communicating with Home Assistant:', error);
      res.status(500).json({ error: 'Internal Server Error' });
    }
  }
  